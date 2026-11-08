package com.omiyage.backend.order

import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import com.omiyage.backend.catalog.InventoryItem
import com.omiyage.backend.catalog.InventoryItemImage
import com.omiyage.backend.catalog.InventoryItemRepository
import com.omiyage.backend.media.MediaService
import com.omiyage.backend.common.Currency
import com.omiyage.backend.errors.CatalogError
import com.omiyage.backend.errors.DomainError
import com.omiyage.backend.errors.OrderError
import com.omiyage.backend.errors.transactionalEither
import com.omiyage.backend.security.MeishiPrincipal
import com.omiyage.backend.security.Role
import com.omiyage.backend.shipment.ExchangeRateService
import com.omiyage.backend.shipment.Shipment
import com.omiyage.backend.shipment.ShipmentRepository
import com.omiyage.backend.shipment.ShipmentStatus
import com.omiyage.backend.user.MeishiAdminClient
import com.omiyage.backend.web.PageResponse
import com.omiyage.backend.web.toPageResponse
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.jvm.optionals.getOrNull

// Once an item has actually been bought or shipped, its content/existence is settled - a user
// can no longer edit or remove it (admin still can, to correct mistakes). Also used by
// DashboardService to tell "money actually spent" apart from "just requested" on a cancelled order.
internal val LOCKED_ITEM_STATUSES = setOf(OrderItemStatus.BOUGHT, OrderItemStatus.SHIPPED)

// Shared by adminMarkOrderPaid and ShipmentService.adminMarkPaid, which both need to flip every
// item under a broader thing (an order, a shipment) paid/unpaid in one go.
internal fun markItemsPaid(items: List<OrderItem>, paid: Boolean) {
    val now = OffsetDateTime.now()
    items.forEach { item ->
        item.paid = paid
        item.paidAt = if (paid) now else null
        item.updatedAt = now
    }
}

// A second identical order from the same user inside this window is a double submit (a double tap
// or a retry), not a new order - createFor hands back the first one instead.
private val DUPLICATE_ORDER_WINDOW = Duration.ofSeconds(30)

private fun CreateOrderItemRequest.signature() = "${inventoryItemId ?: title?.trim()?.lowercase()}x$quantity"

private fun OrderItem.signature() = "${inventoryItem?.id ?: customTitle?.trim()?.lowercase()}x$quantity"

// Admins may skip steps forward (and cancel from any open status) or undo a single step; delivered
// and cancelled orders are final.
internal fun OrderStatus.canMoveTo(target: OrderStatus): Boolean = when {
    this == target -> true
    this == OrderStatus.DELIVERED || this == OrderStatus.CANCELLED -> false
    target == OrderStatus.CANCELLED -> true
    else -> target.ordinal >= ordinal - 1
}

@Service
class OrderService(
    private val orders: OrderRepository,
    private val orderItems: OrderItemRepository,
    private val inventoryItems: InventoryItemRepository,
    private val shipments: ShipmentRepository,
    private val meishiAdmin: MeishiAdminClient,
    private val exchangeRates: ExchangeRateService,
    private val media: MediaService,
    private val orderEvents: OrderEventPublisher,
) {

    private val log = org.slf4j.LoggerFactory.getLogger(OrderService::class.java)
    private val events = org.slf4j.LoggerFactory.getLogger("events")

    @Transactional
    fun create(principal: MeishiPrincipal, request: CreateOrderRequest): Either<DomainError, OrderDto> = createFor(principal.id, request)

    @Transactional
    fun adminCreate(userId: UUID, request: CreateOrderRequest): Either<DomainError, OrderDto> =
        createFor(userId, request, includeProfit = true)

    private fun createFor(userId: UUID, request: CreateOrderRequest, includeProfit: Boolean = false): Either<DomainError, OrderDto> = transactionalEither {
        val nickname = request.nickname?.trim()?.ifBlank { null }
        orders.lockOrderCreation(userId.toString())
        val wanted = request.items.map { it.signature() }.sorted()
        val duplicate = orders.findByUserIdAndCreatedAtAfter(userId, OffsetDateTime.now().minus(DUPLICATE_ORDER_WINDOW))
            .firstOrNull { it.status != OrderStatus.CANCELLED && it.nickname == nickname && it.items.map { item -> item.signature() }.sorted() == wanted }
        if (duplicate != null) {
            duplicate.toDto(includeProfit, exchangeRates::toEur)
        } else {
            val order = Order(userId = userId, notes = request.notes, nickname = nickname)
            request.items.forEach { order.items.add(buildOrderItem(order, it)) }
            val saved = orders.save(order)
            events.info("order_created orderId={} userId={} itemCount={}", saved.id, userId, saved.items.size)
            orderEvents.orderCreated(saved)
            saved.toDto(includeProfit, exchangeRates::toEur)
        }
    }

    @Transactional(readOnly = true)
    fun listMine(principal: MeishiPrincipal, pageable: Pageable): PageResponse<OrderDto> =
        orders.findByUserId(principal.id, withIdTiebreak(pageable)).toPageResponse { it.toDto() }

    @Transactional(readOnly = true)
    fun getById(id: UUID, principal: MeishiPrincipal): Either<DomainError, OrderDto> = transactionalEither {
        val order = findOrRaise(id)
        val isAdmin = principal.role == Role.ADMIN
        ensure(isAdmin || order.userId == principal.id) { OrderError.OrderNotFound }
        order.toDto(includeProfit = isAdmin, toEur = exchangeRates::toEur)
    }

    @Transactional(readOnly = true)
    fun adminList(
        status: OrderStatus?,
        excludeStatus: Set<OrderStatus>,
        sortBy: AdminOrderSortBy,
        direction: Sort.Direction,
        pageable: Pageable,
        authHeader: String,
    ): PageResponse<OrderDto> {
        val page = when (sortBy) {
            AdminOrderSortBy.ITEM_COUNT ->
                if (direction == Sort.Direction.ASC) {
                    orders.findAllOrderByItemCountAsc(status, excludeStatus, pageable)
                } else {
                    orders.findAllOrderByItemCountDesc(status, excludeStatus, pageable)
                }
            else -> {
                val property = if (sortBy == AdminOrderSortBy.CREATED_AT) "createdAt" else "updatedAt"
                val sorted = withIdTiebreak(PageRequest.of(pageable.pageNumber, pageable.pageSize, Sort.by(direction, property)))
                orders.findFiltered(status, excludeStatus, sorted)
            }
        }

        // One admin-panel-scale lookup covers every order on the page - same cost DashboardService
        // already pays to show nicknames next to a user's orders, just reused here for order rows.
        // Nicknames are a display nicety, not core order data - if Meishi is unreachable, the
        // order list itself must still load, just without names for that request.
        val nicknameByUserId = try {
            meishiAdmin.listUsers(authHeader).associate { it.id to it.nickname }
        } catch (e: Exception) {
            log.warn("could not load nicknames for the admin order list: {}", e.message)
            emptyMap()
        }
        return page.toPageResponse { it.toDto(includeProfit = true, toEur = exchangeRates::toEur).copy(userNickname = nicknameByUserId[it.userId]) }
    }

    @Transactional
    fun updateStatus(id: UUID, status: OrderStatus): Either<DomainError, OrderDto> = transactionalEither {
        val order = findOrRaise(id)
        ensure(order.status.canMoveTo(status)) { OrderError.StatusChangeNotAllowed(order.status, status) }
        events.info("order_status_changed orderId={} from={} to={}", id, order.status, status)
        val previousStatus = order.status
        if (status == OrderStatus.CANCELLED) {
            cancelAndDetach(order)
        } else {
            order.status = status
            order.updatedAt = OffsetDateTime.now()
        }
        orderEvents.statusChanged(order, previousStatus)
        order.toDto(includeProfit = true, toEur = exchangeRates::toEur)
    }

    // Bought-for is always JPY (that's the currency the admin actually pays in) and sold-for is
    // always EUR (that's what the customer is charged) - fixed by policy, not a per-item choice,
    // so this ignores whatever currency a caller sends and sets the real one directly.
    @Transactional
    fun updateItem(
        orderId: UUID,
        itemId: UUID,
        status: OrderItemStatus,
        actualPrice: java.math.BigDecimal?,
        soldPrice: java.math.BigDecimal? = null,
        weight: java.math.BigDecimal? = null,
    ): Either<DomainError, OrderDto> = transactionalEither {
        val item = ensureNotNull(orderItems.findById(itemId).getOrNull()) { OrderError.OrderItemNotFound }
        ensure(item.order.id == orderId) { OrderError.OrderItemNotFound }
        events.info("order_item_status_changed orderId={} itemId={} from={} to={}", orderId, itemId, item.status, status)
        item.status = status
        if (status == OrderItemStatus.BOUGHT && item.purchasedAt == null) {
            item.purchasedAt = OffsetDateTime.now()
        }
        item.actualPrice = actualPrice
        item.actualPriceCurrency = if (actualPrice != null) Currency.JPY else null
        item.soldPrice = soldPrice
        item.soldPriceCurrency = if (soldPrice != null) Currency.EUR else null
        item.weight = weight
        item.updatedAt = OffsetDateTime.now()
        item.order.toDto(includeProfit = true, toEur = exchangeRates::toEur)
    }

    @Transactional
    fun addOwnItem(orderId: UUID, principal: MeishiPrincipal, request: CreateOrderItemRequest): Either<DomainError, OrderDto> = transactionalEither {
        val order = findOrRaise(orderId)
        ensure(order.userId == principal.id) { OrderError.OrderNotFound }
        requireOpen(order)
        addItemToOrder(order, request)
    }

    @Transactional
    fun addOwnItems(orderId: UUID, principal: MeishiPrincipal, requests: List<CreateOrderItemRequest>): Either<DomainError, OrderDto> = transactionalEither {
        val order = findOrRaise(orderId)
        ensure(order.userId == principal.id) { OrderError.OrderNotFound }
        requireOpen(order)
        requests.map { addItemToOrder(order, it) }.last()
    }

    @Transactional
    fun adminAddItem(orderId: UUID, request: CreateOrderItemRequest): Either<DomainError, OrderDto> = transactionalEither {
        addItemToOrder(findOrRaise(orderId), request, includeProfit = true)
    }

    @Transactional
    fun updateOwnItem(orderId: UUID, itemId: UUID, principal: MeishiPrincipal, request: UpdateOwnOrderItemRequest): Either<DomainError, OrderDto> = transactionalEither {
        val order = findOrRaise(orderId)
        ensure(order.userId == principal.id) { OrderError.OrderNotFound }
        requireOpen(order)
        applyItemContentEdit(ownItemOrRaise(order, itemId), request, enforceStatusGuard = true)
        order.toDto()
    }

    @Transactional
    fun adminUpdateItemContent(orderId: UUID, itemId: UUID, request: UpdateOwnOrderItemRequest): Either<DomainError, OrderDto> = transactionalEither {
        val order = findOrRaise(orderId)
        applyItemContentEdit(ownItemOrRaise(order, itemId), request, enforceStatusGuard = false)
        order.toDto(includeProfit = true, toEur = exchangeRates::toEur)
    }

    // A custom (non-catalog) request is not in Inventory until an admin decides it belongs there.
    // Added at zero stock, which already means "still requestable, just signals the admin to go
    // source it" (see buildOrderItem's inventoryItemId branch), so no new status is needed.
    @Transactional
    fun adminAddItemToInventory(orderId: UUID, itemId: UUID): Either<DomainError, OrderDto> = transactionalEither {
        val order = findOrRaise(orderId)
        val item = ownItemOrRaise(order, itemId)
        val title = item.customTitle
        val condition = item.condition
        ensure(item.inventoryItem == null && title != null && condition != null) { OrderError.ItemNotCustom }
        ensure(!inventoryItems.existsByTitleIgnoreCaseAndConditionAndLink(title, condition, item.link)) {
            OrderError.InventoryItemAlreadyExists
        }
        val newItem = InventoryItem(
            title = title,
            description = item.customDescription,
            link = item.link,
            condition = condition,
            priceAmount = item.maxPrice ?: java.math.BigDecimal.ZERO,
            currency = item.currency,
            stockQuantity = 0,
            isPreorder = item.isPreorder,
            expectedReleaseDate = item.expectedReleaseDate,
        )
        item.customImageKey?.let { newItem.images.add(InventoryItemImage(inventoryItem = newItem, objectKey = it)) }
        inventoryItems.save(newItem)
        order.toDto(includeProfit = true, toEur = exchangeRates::toEur)
    }

    @Transactional
    fun adminMarkItemPaid(orderId: UUID, itemId: UUID, paid: Boolean): Either<DomainError, OrderDto> = transactionalEither {
        val order = findOrRaise(orderId)
        val item = ownItemOrRaise(order, itemId)
        item.paid = paid
        item.paidAt = if (paid) OffsetDateTime.now() else null
        item.updatedAt = OffsetDateTime.now()
        order.toDto(includeProfit = true, toEur = exchangeRates::toEur)
    }

    @Transactional
    fun adminMarkOrderPaid(orderId: UUID, paid: Boolean): Either<DomainError, OrderDto> = transactionalEither {
        val order = findOrRaise(orderId)
        markItemsPaid(order.items, paid)
        order.toDto(includeProfit = true, toEur = exchangeRates::toEur)
    }

    @Transactional
    fun removeOwnItem(orderId: UUID, itemId: UUID, principal: MeishiPrincipal): Either<DomainError, OrderDto> = transactionalEither {
        val order = findOrRaise(orderId)
        ensure(order.userId == principal.id) { OrderError.OrderNotFound }
        requireOpen(order)
        removeItemFromOrder(order, ownItemOrRaise(order, itemId), enforceStatusGuard = true)
        order.toDto()
    }

    @Transactional
    fun adminRemoveItem(orderId: UUID, itemId: UUID): Either<DomainError, OrderDto> = transactionalEither {
        val order = findOrRaise(orderId)
        removeItemFromOrder(order, ownItemOrRaise(order, itemId), enforceStatusGuard = false)
        order.toDto(includeProfit = true, toEur = exchangeRates::toEur)
    }

    @Transactional(readOnly = true)
    fun adminOrdersForUser(userId: UUID): List<OrderDto> =
        orders.findByUserId(userId, Pageable.unpaged()).content.map { it.toDto(includeProfit = true, toEur = exchangeRates::toEur) }

    // Admin equivalent of splitMine, but the items can land on an existing order of the same user
    // instead of always spinning up a new one - targetOrderId null still creates a new order, same
    // as splitMine does.
    @Transactional
    fun adminMoveItems(sourceOrderId: UUID, itemIds: List<UUID>, targetOrderId: UUID?): Either<DomainError, MoveItemsResponseDto> = transactionalEither {
        val source = findOrRaise(sourceOrderId)
        val distinctIds = itemIds.distinct()
        val toMove = distinctIds.map { id ->
            ensureNotNull(source.items.find { it.id == id }) { OrderError.OrderItemIdNotFound(id) }
        }
        ensure(toMove.isNotEmpty() && toMove.size != source.items.size) { OrderError.MoveNeedsSomeButNotAllItems }
        val target = if (targetOrderId != null) {
            ensure(targetOrderId != sourceOrderId) { OrderError.MoveTargetIsSource }
            val existing = findOrRaise(targetOrderId)
            ensure(existing.userId == source.userId) { OrderError.MoveTargetOfOtherUser }
            existing
        } else {
            orders.save(Order(userId = source.userId, status = source.status))
        }
        toMove.forEach { item ->
            source.items.remove(item)
            item.order = target
            target.items.add(item)
        }
        source.updatedAt = OffsetDateTime.now()
        target.updatedAt = OffsetDateTime.now()
        events.info("items_moved sourceOrderId={} targetOrderId={} itemCount={}", source.id, target.id, toMove.size)
        MoveItemsResponseDto(
            source = source.toDto(includeProfit = true, toEur = exchangeRates::toEur),
            target = target.toDto(includeProfit = true, toEur = exchangeRates::toEur),
        )
    }

    @Transactional
    fun cancelOwn(orderId: UUID, principal: MeishiPrincipal): Either<DomainError, OrderDto> = transactionalEither {
        val order = findOrRaise(orderId)
        ensure(order.userId == principal.id) { OrderError.OrderNotFound }
        requireOpen(order)
        ensure(order.items.none { it.status in LOCKED_ITEM_STATUSES }) { OrderError.CancelHasLockedItems }
        val previousStatus = order.status
        cancelAndDetach(order)
        orderEvents.statusChanged(order, previousStatus)
        events.info("order_cancelled orderId={} userId={}", orderId, principal.id)
        order.toDto()
    }

    // Mirrors mergeMine's validation shape, in reverse: pulls the chosen items off the source order
    // and onto a brand-new one instead of folding multiple orders together. The new order is never
    // attached to a shipment, so splitting an item out of a combined shipment falls out of this for
    // free - no separate "remove item from shipment" code path needed.
    @Transactional
    fun splitMine(principal: MeishiPrincipal, orderId: UUID, itemIds: List<UUID>): Either<DomainError, SplitOrderResponseDto> = transactionalEither {
        val distinctIds = itemIds.distinct()
        val source = findOrRaise(orderId)
        ensure(source.userId == principal.id) { OrderError.OrderNotFound }
        requireOpen(source)
        val toMove = distinctIds.map { id ->
            ensureNotNull(source.items.find { it.id == id }) { OrderError.OrderItemIdNotFound(id) }
        }
        ensure(toMove.isNotEmpty() && toMove.size != source.items.size) { OrderError.SplitNeedsSomeButNotAllItems }

        val created = Order(userId = source.userId, status = source.status)
        orders.save(created)
        toMove.forEach { item ->
            source.items.remove(item)
            item.order = created
            created.items.add(item)
        }
        source.updatedAt = OffsetDateTime.now()
        events.info("order_split sourceOrderId={} newOrderId={} itemCount={}", source.id, created.id, toMove.size)
        SplitOrderResponseDto(original = source.toDto(), created = created.toDto())
    }

    // Only the order's own owner (no shipment yet) or the shipment's owner (set when orders were
    // combined - see ShipmentService.create) may submit it. Submitting flips every still-open
    // order on the shipment to PREPARING_SHIPPING, not just the one the caller named, since the
    // shipment moves as a unit even if it holds other users' orders too.
    @Transactional
    fun submitForShipping(orderId: UUID, principal: MeishiPrincipal): Either<DomainError, OrderDto> = transactionalEither {
        val order = findOrRaise(orderId)
        val existingShipment = order.shipment
        // A shipment with no owner assigned authorizes nobody - it must never fall back to "the
        // order named in the URL happens to be this caller's", or any co-member of a shipment
        // could submit it by naming their own order, bypassing the owner-only rule entirely.
        val authorizedUserId = if (existingShipment == null) order.userId else existingShipment.ownerUserId
        ensure(authorizedUserId != null && authorizedUserId == principal.id) { OrderError.OrderNotFound }
        requireOpen(order)
        // A preorder item has nothing to ship yet - it hasn't released, so there's no physical
        // package for the courier to take. The order can still be combined with others or split
        // off (see ShipmentService.combine/splitOff), just not submitted while this is unresolved.
        ensure(order.items.none { it.isPreorder }) { OrderError.SubmitHasPreorderItem }
        // Every submitted order needs a shipment to track it through delivery, even a solo one
        // nobody ever explicitly combined - create one now instead of leaving it shipment-less
        // (which used to mean it never showed up on the member's own shipments list).
        val shipment = existingShipment ?: shipments.save(Shipment(ownerUserId = order.userId)).also {
            order.shipment = it
            it.orders.add(order)
        }
        val targets = shipment.orders.filter { it.isOpen && it.items.none { item -> item.isPreorder } }
        targets.forEach { target ->
            val previousStatus = target.status
            target.status = OrderStatus.PREPARING_SHIPPING
            target.updatedAt = OffsetDateTime.now()
            orderEvents.statusChanged(target, previousStatus)
        }
        events.info("order_submitted_for_shipping orderId={} shipmentId={} orderCount={}", orderId, shipment.id, targets.size)
        order.toDto()
    }

    @Transactional
    fun adminDelete(orderId: UUID): Either<DomainError, Unit> = transactionalEither<Unit> {
        val order = findOrRaise(orderId)
        val shipmentId = order.shipment?.id
        orders.delete(order)
        // Deleting the order can leave its shipment referenced by no order at all - same cleanup
        // as the merge/combine/reassign paths, just triggered by the order disappearing outright.
        if (shipmentId != null && !orders.existsByShipment_Id(shipmentId)) {
            shipments.deleteById(shipmentId)
        }
    }

    @Transactional
    fun mergeMine(principal: MeishiPrincipal, orderIds: List<UUID>): Either<DomainError, OrderDto> = transactionalEither {
        val selected = loadDistinctOrRaise(orderIds)
        selected.forEach { order -> ensure(order.userId == principal.id) { OrderError.OrderNotFound } }
        mergeSelected(selected).also { events.info("orders_merged targetOrderId={} mergedCount={}", it.id, selected.size) }.toDto()
    }

    // Admin equivalent of mergeMine: no ownership restriction (any user's orders), but merging
    // across different users would silently move one person's items onto another's order, so
    // that's blocked here instead - same-user-only is the one rule mergeMine gets for free from
    // "every selected order belongs to the calling principal" that this doesn't.
    @Transactional
    fun adminMerge(orderIds: List<UUID>): Either<DomainError, OrderDto> = transactionalEither {
        val selected = loadDistinctOrRaise(orderIds)
        ensure(selected.map { it.userId }.distinct().size <= 1) { OrderError.MergeAcrossUsers }
        mergeSelected(selected).also { events.info("orders_merged targetOrderId={} mergedCount={}", it.id, selected.size) }
            .toDto(includeProfit = true, toEur = exchangeRates::toEur)
    }

    private fun Raise<DomainError>.loadDistinctOrRaise(orderIds: List<UUID>): List<Order> {
        val distinctIds = orderIds.distinct()
        ensure(distinctIds.size >= 2) { OrderError.MergeNeedsTwoOrders }
        return distinctIds.map { findOrRaise(it) }
    }

    /** Folds every selected order's items onto one target order and deletes the rest. Shared by
     *  mergeMine and adminMerge, which differ only in who may select which orders (see each). */
    private fun Raise<DomainError>.mergeSelected(selected: List<Order>): Order {
        selected.forEach { order ->
            requireMergeableAsTarget(order)
            val shipmentStatus = order.shipment?.status
            ensure(shipmentStatus == null || shipmentStatus == ShipmentStatus.PREPARING) { OrderError.MergeShippedOrder }
        }
        val preparingShipping = selected.filter { it.status == OrderStatus.PREPARING_SHIPPING }
        ensure(preparingShipping.size <= 1) { OrderError.MergeBothSubmitted }
        // A submitted-but-not-yet-shipped order is always the target, whichever position the
        // caller selected it in - it's the one order here that can't simply become a "source" and
        // get deleted, since its own status/shipment are what the merge needs to preserve. Every
        // other selected order still has to be a normal open order (requireOpen below).
        val target = preparingShipping.firstOrNull() ?: selected.first()
        val sources = selected.filter { it.id != target.id }
        sources.forEach { requireOpen(it) }
        // The target keeps its own shipment if it has one (this is how a PREPARING_SHIPPING target
        // stays on its shipment). Otherwise, adopt whichever source's shipment comes first instead
        // of letting it get vacated below - the merged items belong wherever that shipment is
        // already headed, not nowhere.
        if (target.shipment == null) {
            sources.firstNotNullOfOrNull { it.shipment }?.let { donorShipment ->
                target.shipment = donorShipment
                donorShipment.orders.add(target)
            }
        }
        val vacatedShipmentIds = sources.mapNotNull { it.shipment?.id }
            .distinct()
            .filter { it != target.shipment?.id }
        sources.forEach { source ->
            source.items.toList().forEach { item ->
                source.items.remove(item)
                item.order = target
                target.items.add(item)
            }
            target.notes = listOfNotNull(
                target.notes?.takeIf { it.isNotBlank() },
                source.notes?.takeIf { it.isNotBlank() },
            ).joinToString("\n").ifBlank { null }
        }
        orders.deleteAll(sources)
        // A source order's shipment can end up referenced by no order at all once its orders
        // are merged away — delete it too instead of leaving an empty shipment behind.
        vacatedShipmentIds.forEach { shipmentId ->
            if (!orders.existsByShipment_Id(shipmentId)) {
                shipments.deleteById(shipmentId)
            }
        }
        target.updatedAt = OffsetDateTime.now()
        return target
    }

    @Transactional
    fun updateNickname(orderId: UUID, principal: MeishiPrincipal, nickname: String?): Either<DomainError, OrderDto> = transactionalEither {
        val order = findOrRaise(orderId)
        ensure(order.userId == principal.id) { OrderError.OrderNotFound }
        applyNickname(order, nickname)
    }

    // Admin equivalent of updateNickname: no ownership restriction - an admin can title any
    // user's order, e.g. to label it for a shipment before the owner gets around to it.
    @Transactional
    fun adminUpdateNickname(orderId: UUID, nickname: String?): Either<DomainError, OrderDto> = transactionalEither {
        applyNickname(findOrRaise(orderId), nickname)
    }

    private fun applyNickname(order: Order, nickname: String?): OrderDto {
        order.nickname = nickname?.trim()?.ifBlank { null }
        order.updatedAt = OffsetDateTime.now()
        return order.toDto()
    }

    // A cancelled order has nothing left to ship - leaving it attached would keep billing it a
    // shipping-cost share and counting its weight against the shipment (see ShipmentDtos.toDto).
    // Shared by both the user-facing cancel and the admin status-update path so neither can
    // leave a cancelled order dangling on a shipment.
    private fun cancelAndDetach(order: Order) {
        // Nothing was actually taken for a cancelled order - restore any stock its inventory-
        // linked items had reserved. Guarded on the prior status so re-cancelling an
        // already-cancelled order (e.g. a redundant admin status update) can't double-restock.
        if (order.status != OrderStatus.CANCELLED) {
            order.items.forEach { restockIfLinked(it) }
        }
        order.status = OrderStatus.CANCELLED
        order.updatedAt = OffsetDateTime.now()
        val shipmentId = order.shipment?.id
        if (shipmentId != null) {
            order.shipment = null
            if (!orders.existsByShipment_Id(shipmentId)) {
                shipments.deleteById(shipmentId)
            }
        }
    }

    private fun Raise<DomainError>.requireOpen(order: Order) {
        ensure(order.isOpen) { OrderError.OrderClosed }
    }

    // Same as requireOpen, but also allows PREPARING_SHIPPING - only for the order other orders
    // are being merged into, never for an order being edited/added to on its own.
    private fun Raise<DomainError>.requireMergeableAsTarget(order: Order) {
        ensure(order.status !in setOf(OrderStatus.SENT, OrderStatus.DELIVERED, OrderStatus.CANCELLED)) { OrderError.OrderClosed }
    }

    private fun Raise<DomainError>.addItemToOrder(order: Order, request: CreateOrderItemRequest, includeProfit: Boolean = false): OrderDto {
        val item = buildOrderItem(order, request)
        order.items.add(item)
        // order is already persistent here (unlike create(), which persists a brand-new Order and
        // gets cascaded inserts for free) - save explicitly so the new item's id is generated now,
        // not deferred to flush, since toDto() below needs it immediately.
        orderItems.save(item)
        order.updatedAt = OffsetDateTime.now()
        return order.toDto(includeProfit, exchangeRates::toEur)
    }

    private fun Raise<DomainError>.applyItemContentEdit(item: OrderItem, request: UpdateOwnOrderItemRequest, enforceStatusGuard: Boolean) {
        ensure(!enforceStatusGuard || item.status !in LOCKED_ITEM_STATUSES) { OrderError.ItemLockedForEdit }
        request.title?.let { item.customTitle = it }
        request.description?.let { item.customDescription = it }
        request.imageKey?.let {
            media.requireExisting(listOf(it)).bind()
            item.customImageKey = it
        }
        request.maxPrice?.let { item.maxPrice = it }
        request.quantity?.let { newQuantity ->
            item.inventoryItem?.let { inv ->
                val available = inv.stockQuantity + item.reservedQuantity
                item.reservedQuantity = minOf(newQuantity, available)
                inv.stockQuantity = available - item.reservedQuantity
            }
            item.quantity = newQuantity
        }
        request.link?.let { item.link = it }
        item.updatedAt = OffsetDateTime.now()
    }

    private fun Raise<DomainError>.removeItemFromOrder(order: Order, item: OrderItem, enforceStatusGuard: Boolean) {
        ensure(!enforceStatusGuard || item.status !in LOCKED_ITEM_STATUSES) { OrderError.ItemLockedForRemoval }
        restockIfLinked(item)
        order.items.remove(item)
        orderItems.delete(item)
        order.updatedAt = OffsetDateTime.now()
    }

    // Mirrors buildOrderItem's decrement - removing/cancelling an inventory-linked item means
    // nothing was actually taken, so give back exactly what was reserved.
    private fun restockIfLinked(item: OrderItem) {
        item.inventoryItem?.let { it.stockQuantity += item.reservedQuantity }
        item.reservedQuantity = 0
    }

    private fun Raise<DomainError>.ownItemOrRaise(order: Order, itemId: UUID): OrderItem {
        val item = ensureNotNull(orderItems.findById(itemId).getOrNull()) { OrderError.OrderItemNotFound }
        ensure(item.order.id == order.id) { OrderError.OrderItemNotFound }
        return item
    }

    private fun Raise<DomainError>.buildOrderItem(order: Order, request: CreateOrderItemRequest): OrderItem {
        if (request.inventoryItemId != null) {
            val inventoryItem = ensureNotNull(inventoryItems.findById(request.inventoryItemId).getOrNull()) { CatalogError.InventoryItemNotFound }
            // Never go negative and never block the request over it - an item at (or now hitting)
            // 0 stock is still requestable, it's just a signal to the admin to restock/rebuy.
            val reserved = minOf(request.quantity, inventoryItem.stockQuantity)
            inventoryItem.stockQuantity -= reserved
            return OrderItem(
                order = order,
                inventoryItem = inventoryItem,
                reservedQuantity = reserved,
                // Max price is always EUR - fixed by policy, not a per-item choice (see
                // OrderDtos.UpdateOrderItemRequest for the equivalent on the admin bookkeeping side).
                currency = Currency.EUR,
                maxPrice = request.maxPrice,
                // Cart/order items linked to inventory charge the catalog price, not an
                // admin-entered figure - stamp it at add-time so profit is computable immediately.
                // updateItem() above does a full replace of soldPrice from whatever a caller sends
                // (null wipes it) - any future caller of that endpoint must resend the current
                // soldPrice to preserve it, same as the existing admin edit dialog does.
                soldPrice = inventoryItem.priceAmount,
                soldPriceCurrency = Currency.EUR,
                // Same stamp-at-add-time convention as soldPrice above - a linked item's catalog
                // weight is known immediately instead of waiting for an admin to fill it in later.
                weight = inventoryItem.weight,
                // boughtPrice is always JPY (see CatalogDtos' profitEur conversion) - stamp it
                // alongside soldPrice for the same reason: profit/cost is computable immediately
                // instead of waiting on the admin to re-enter a cost they already recorded once.
                actualPrice = inventoryItem.boughtPrice,
                actualPriceCurrency = inventoryItem.boughtPrice?.let { Currency.JPY },
                quantity = request.quantity,
                link = request.link,
                // Preorder status/date is catalog-controlled, not user-supplied - inherit from the
                // linked item rather than trusting request.isPreorder, which would let a member
                // spoof a preorder badge or date on a normal in-stock item.
                isPreorder = inventoryItem.isPreorder,
                expectedReleaseDate = inventoryItem.expectedReleaseDate,
            )
        }

        ensure(!request.title.isNullOrBlank()) { OrderError.ItemNeedsInventoryItemOrTitle }
        ensure(request.condition != null) { OrderError.CustomItemNeedsCondition }
        ensure(!request.isPreorder || request.expectedReleaseDate != null) { OrderError.PreorderNeedsReleaseDate }
        request.imageKey?.let { media.requireExisting(listOf(it)).bind() }
        return OrderItem(
            order = order,
            customTitle = request.title,
            customDescription = request.description,
            customImageKey = request.imageKey,
            condition = request.condition,
            currency = request.currency,
            maxPrice = request.maxPrice,
            quantity = request.quantity,
            link = request.link,
            isPreorder = request.isPreorder,
            expectedReleaseDate = request.expectedReleaseDate,
        )
    }


    // Orders often share a timestamp; without a unique last sort key, Postgres may order the ties
    // differently on each page, so paging repeats some orders and skips others (#101).
    private fun withIdTiebreak(pageable: Pageable): Pageable =
        if (pageable.isUnpaged) pageable else PageRequest.of(pageable.pageNumber, pageable.pageSize, pageable.sort.and(Sort.by("id")))

    private fun Raise<DomainError>.findOrRaise(id: UUID): Order =
        ensureNotNull(orders.findById(id).getOrNull()) { OrderError.OrderNotFound }
}
