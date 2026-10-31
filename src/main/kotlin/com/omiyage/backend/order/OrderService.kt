package com.omiyage.backend.order

import com.omiyage.backend.catalog.InventoryItem
import com.omiyage.backend.catalog.InventoryItemImage
import com.omiyage.backend.catalog.InventoryItemRepository
import com.omiyage.backend.media.MediaService
import com.omiyage.backend.common.Currency
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
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.OffsetDateTime
import java.util.UUID

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
) {

    private val log = org.slf4j.LoggerFactory.getLogger(OrderService::class.java)
    private val events = org.slf4j.LoggerFactory.getLogger("events")

    @Transactional
    fun create(principal: MeishiPrincipal, request: CreateOrderRequest): OrderDto = createFor(principal.id, request)

    @Transactional
    fun adminCreate(userId: UUID, request: CreateOrderRequest): OrderDto = createFor(userId, request, includeProfit = true)

    private fun createFor(userId: UUID, request: CreateOrderRequest, includeProfit: Boolean = false): OrderDto {
        val order = Order(userId = userId, notes = request.notes, nickname = request.nickname?.trim()?.ifBlank { null })
        request.items.forEach { order.items.add(buildOrderItem(order, it)) }
        val saved = orders.save(order)
        events.info("order_created orderId={} userId={} itemCount={}", saved.id, userId, saved.items.size)
        return saved.toDto(includeProfit, exchangeRates::toEur)
    }

    @Transactional(readOnly = true)
    fun listMine(principal: MeishiPrincipal, pageable: Pageable): PageResponse<OrderDto> =
        orders.findByUserId(principal.id, pageable).toPageResponse { it.toDto() }

    @Transactional(readOnly = true)
    fun getById(id: UUID, principal: MeishiPrincipal): OrderDto {
        val order = findOrThrow(id)
        val isAdmin = principal.role == Role.ADMIN
        if (!isAdmin && order.userId != principal.id) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "order not found")
        }
        return order.toDto(includeProfit = isAdmin, toEur = exchangeRates::toEur)
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
                val sorted = PageRequest.of(pageable.pageNumber, pageable.pageSize, Sort.by(direction, property))
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
    fun updateStatus(id: UUID, status: OrderStatus): OrderDto {
        val order = findOrThrow(id)
        if (!order.status.canMoveTo(status)) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "cannot change order status from ${order.status} to $status")
        }
        events.info("order_status_changed orderId={} from={} to={}", id, order.status, status)
        if (status == OrderStatus.CANCELLED) {
            cancelAndDetach(order)
        } else {
            order.status = status
            order.updatedAt = OffsetDateTime.now()
        }
        return order.toDto(includeProfit = true, toEur = exchangeRates::toEur)
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
    ): OrderDto {
        val item = orderItems.findById(itemId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "order item not found") }
        if (item.order.id != orderId) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "order item not found")
        }
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
        return item.order.toDto(includeProfit = true, toEur = exchangeRates::toEur)
    }

    @Transactional
    fun addOwnItem(orderId: UUID, principal: MeishiPrincipal, request: CreateOrderItemRequest): OrderDto {
        val order = findOrThrow(orderId)
        if (order.userId != principal.id) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "order not found")
        }
        requireOpen(order)
        return addItemToOrder(order, request)
    }

    @Transactional
    fun addOwnItems(orderId: UUID, principal: MeishiPrincipal, requests: List<CreateOrderItemRequest>): OrderDto {
        val order = findOrThrow(orderId)
        if (order.userId != principal.id) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "order not found")
        }
        requireOpen(order)
        return requests.map { addItemToOrder(order, it) }.last()
    }

    @Transactional
    fun adminAddItem(orderId: UUID, request: CreateOrderItemRequest): OrderDto =
        addItemToOrder(findOrThrow(orderId), request, includeProfit = true)

    @Transactional
    fun updateOwnItem(orderId: UUID, itemId: UUID, principal: MeishiPrincipal, request: UpdateOwnOrderItemRequest): OrderDto {
        val order = findOrThrow(orderId)
        if (order.userId != principal.id) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "order not found")
        }
        requireOpen(order)
        applyItemContentEdit(ownItemOrThrow(order, itemId), request, enforceStatusGuard = true)
        return order.toDto()
    }

    @Transactional
    fun adminUpdateItemContent(orderId: UUID, itemId: UUID, request: UpdateOwnOrderItemRequest): OrderDto {
        val order = findOrThrow(orderId)
        applyItemContentEdit(ownItemOrThrow(order, itemId), request, enforceStatusGuard = false)
        return order.toDto(includeProfit = true, toEur = exchangeRates::toEur)
    }

    @Transactional
    fun adminMarkItemPaid(orderId: UUID, itemId: UUID, paid: Boolean): OrderDto {
        val order = findOrThrow(orderId)
        val item = ownItemOrThrow(order, itemId)
        item.paid = paid
        item.paidAt = if (paid) OffsetDateTime.now() else null
        item.updatedAt = OffsetDateTime.now()
        return order.toDto(includeProfit = true, toEur = exchangeRates::toEur)
    }

    @Transactional
    fun adminMarkOrderPaid(orderId: UUID, paid: Boolean): OrderDto {
        val order = findOrThrow(orderId)
        markItemsPaid(order.items, paid)
        return order.toDto(includeProfit = true, toEur = exchangeRates::toEur)
    }

    @Transactional
    fun removeOwnItem(orderId: UUID, itemId: UUID, principal: MeishiPrincipal): OrderDto {
        val order = findOrThrow(orderId)
        if (order.userId != principal.id) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "order not found")
        }
        requireOpen(order)
        removeItemFromOrder(order, ownItemOrThrow(order, itemId), enforceStatusGuard = true)
        return order.toDto()
    }

    @Transactional
    fun adminRemoveItem(orderId: UUID, itemId: UUID): OrderDto {
        val order = findOrThrow(orderId)
        removeItemFromOrder(order, ownItemOrThrow(order, itemId), enforceStatusGuard = false)
        return order.toDto(includeProfit = true, toEur = exchangeRates::toEur)
    }

    @Transactional(readOnly = true)
    fun adminOrdersForUser(userId: UUID): List<OrderDto> =
        orders.findByUserId(userId, Pageable.unpaged()).content.map { it.toDto(includeProfit = true, toEur = exchangeRates::toEur) }

    // Admin equivalent of splitMine, but the items can land on an existing order of the same user
    // instead of always spinning up a new one - targetOrderId null still creates a new order, same
    // as splitMine does.
    @Transactional
    fun adminMoveItems(sourceOrderId: UUID, itemIds: List<UUID>, targetOrderId: UUID?): MoveItemsResponseDto {
        val source = findOrThrow(sourceOrderId)
        val distinctIds = itemIds.distinct()
        val toMove = distinctIds.map { id ->
            source.items.find { it.id == id }
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "order item not found: $id")
        }
        if (toMove.isEmpty() || toMove.size == source.items.size) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "must move at least one item and leave at least one behind")
        }
        val target = if (targetOrderId != null) {
            if (targetOrderId == sourceOrderId) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "target order must be different from the source order")
            }
            val existing = findOrThrow(targetOrderId)
            if (existing.userId != source.userId) {
                throw ResponseStatusException(HttpStatus.CONFLICT, "cannot move items to an order belonging to a different user")
            }
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
        return MoveItemsResponseDto(
            source = source.toDto(includeProfit = true, toEur = exchangeRates::toEur),
            target = target.toDto(includeProfit = true, toEur = exchangeRates::toEur),
        )
    }

    @Transactional
    fun cancelOwn(orderId: UUID, principal: MeishiPrincipal): OrderDto {
        val order = findOrThrow(orderId)
        if (order.userId != principal.id) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "order not found")
        }
        requireOpen(order)
        if (order.items.any { it.status in LOCKED_ITEM_STATUSES }) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "order has items already bought or shipped and cannot be cancelled")
        }
        cancelAndDetach(order)
        events.info("order_cancelled orderId={} userId={}", orderId, principal.id)
        return order.toDto()
    }

    // Mirrors mergeMine's validation shape, in reverse: pulls the chosen items off the source order
    // and onto a brand-new one instead of folding multiple orders together. The new order is never
    // attached to a shipment, so splitting an item out of a combined shipment falls out of this for
    // free - no separate "remove item from shipment" code path needed.
    @Transactional
    fun splitMine(principal: MeishiPrincipal, orderId: UUID, itemIds: List<UUID>): SplitOrderResponseDto {
        val distinctIds = itemIds.distinct()
        val source = findOrThrow(orderId)
        if (source.userId != principal.id) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "order not found")
        }
        requireOpen(source)
        val toMove = distinctIds.map { id ->
            source.items.find { it.id == id }
                ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "order item not found: $id")
        }
        if (toMove.isEmpty() || toMove.size == source.items.size) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "split must move at least one item and leave at least one behind")
        }

        val created = Order(userId = source.userId, status = source.status)
        orders.save(created)
        toMove.forEach { item ->
            source.items.remove(item)
            item.order = created
            created.items.add(item)
        }
        source.updatedAt = OffsetDateTime.now()
        events.info("order_split sourceOrderId={} newOrderId={} itemCount={}", source.id, created.id, toMove.size)
        return SplitOrderResponseDto(original = source.toDto(), created = created.toDto())
    }

    // Only the order's own owner (no shipment yet) or the shipment's owner (set when orders were
    // combined - see ShipmentService.create) may submit it. Submitting flips every still-open
    // order on the shipment to PREPARING_SHIPPING, not just the one the caller named, since the
    // shipment moves as a unit even if it holds other users' orders too.
    @Transactional
    fun submitForShipping(orderId: UUID, principal: MeishiPrincipal): OrderDto {
        val order = findOrThrow(orderId)
        val existingShipment = order.shipment
        // A shipment with no owner assigned authorizes nobody - it must never fall back to "the
        // order named in the URL happens to be this caller's", or any co-member of a shipment
        // could submit it by naming their own order, bypassing the owner-only rule entirely.
        val authorizedUserId = if (existingShipment == null) order.userId else existingShipment.ownerUserId
        if (authorizedUserId == null || authorizedUserId != principal.id) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "order not found")
        }
        requireOpen(order)
        // A preorder item has nothing to ship yet - it hasn't released, so there's no physical
        // package for the courier to take. The order can still be combined with others or split
        // off (see ShipmentService.combine/splitOff), just not submitted while this is unresolved.
        if (order.items.any { it.isPreorder }) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "order has a preorder item that has not released yet and cannot be submitted for shipping")
        }
        // Every submitted order needs a shipment to track it through delivery, even a solo one
        // nobody ever explicitly combined - create one now instead of leaving it shipment-less
        // (which used to mean it never showed up on the member's own shipments list).
        val shipment = existingShipment ?: shipments.save(Shipment(ownerUserId = order.userId)).also {
            order.shipment = it
            it.orders.add(order)
        }
        val targets = shipment.orders.filter { it.isOpen && it.items.none { item -> item.isPreorder } }
        targets.forEach { target ->
            target.status = OrderStatus.PREPARING_SHIPPING
            target.updatedAt = OffsetDateTime.now()
        }
        events.info("order_submitted_for_shipping orderId={} shipmentId={} orderCount={}", orderId, shipment.id, targets.size)
        return order.toDto()
    }

    @Transactional
    fun adminDelete(orderId: UUID) {
        val order = findOrThrow(orderId)
        val shipmentId = order.shipment?.id
        orders.delete(order)
        // Deleting the order can leave its shipment referenced by no order at all - same cleanup
        // as the merge/combine/reassign paths, just triggered by the order disappearing outright.
        if (shipmentId != null && !orders.existsByShipment_Id(shipmentId)) {
            shipments.deleteById(shipmentId)
        }
    }

    @Transactional
    fun mergeMine(principal: MeishiPrincipal, orderIds: List<UUID>): OrderDto {
        val selected = loadDistinctOrThrow(orderIds)
        selected.forEach { order ->
            if (order.userId != principal.id) {
                throw ResponseStatusException(HttpStatus.NOT_FOUND, "order not found")
            }
        }
        return mergeSelected(selected).also { events.info("orders_merged targetOrderId={} mergedCount={}", it.id, selected.size) }.toDto()
    }

    // Admin equivalent of mergeMine: no ownership restriction (any user's orders), but merging
    // across different users would silently move one person's items onto another's order, so
    // that's blocked here instead - same-user-only is the one rule mergeMine gets for free from
    // "every selected order belongs to the calling principal" that this doesn't.
    @Transactional
    fun adminMerge(orderIds: List<UUID>): OrderDto {
        val selected = loadDistinctOrThrow(orderIds)
        if (selected.map { it.userId }.distinct().size > 1) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "cannot merge orders belonging to different users")
        }
        return mergeSelected(selected).also { events.info("orders_merged targetOrderId={} mergedCount={}", it.id, selected.size) }
            .toDto(includeProfit = true, toEur = exchangeRates::toEur)
    }

    private fun loadDistinctOrThrow(orderIds: List<UUID>): List<Order> {
        val distinctIds = orderIds.distinct()
        if (distinctIds.size < 2) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "select at least two different orders to merge")
        }
        return distinctIds.map { findOrThrow(it) }
    }

    /** Folds every selected order's items onto one target order and deletes the rest. Shared by
     *  mergeMine and adminMerge, which differ only in who may select which orders (see each). */
    private fun mergeSelected(selected: List<Order>): Order {
        selected.forEach { order ->
            requireMergeableAsTarget(order)
            val shipmentStatus = order.shipment?.status
            if (shipmentStatus != null && shipmentStatus != ShipmentStatus.PREPARING) {
                throw ResponseStatusException(HttpStatus.CONFLICT, "an order that has already shipped cannot be merged")
            }
        }
        val preparingShipping = selected.filter { it.status == OrderStatus.PREPARING_SHIPPING }
        if (preparingShipping.size > 1) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "cannot merge two orders that have both already been submitted for shipping")
        }
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
    fun updateNickname(orderId: UUID, principal: MeishiPrincipal, nickname: String?): OrderDto {
        val order = findOrThrow(orderId)
        if (order.userId != principal.id) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "order not found")
        }
        return applyNickname(order, nickname)
    }

    // Admin equivalent of updateNickname: no ownership restriction - an admin can title any
    // user's order, e.g. to label it for a shipment before the owner gets around to it.
    @Transactional
    fun adminUpdateNickname(orderId: UUID, nickname: String?): OrderDto = applyNickname(findOrThrow(orderId), nickname)

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

    private fun requireOpen(order: Order) {
        if (!order.isOpen) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "order is closed and can no longer be edited")
        }
    }

    // Same as requireOpen, but also allows PREPARING_SHIPPING - only for the order other orders
    // are being merged into, never for an order being edited/added to on its own.
    private fun requireMergeableAsTarget(order: Order) {
        if (order.status in setOf(OrderStatus.SENT, OrderStatus.DELIVERED, OrderStatus.CANCELLED)) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "order is closed and can no longer be edited")
        }
    }

    private fun addItemToOrder(order: Order, request: CreateOrderItemRequest, includeProfit: Boolean = false): OrderDto {
        val item = buildOrderItem(order, request)
        order.items.add(item)
        // order is already persistent here (unlike create(), which persists a brand-new Order and
        // gets cascaded inserts for free) - save explicitly so the new item's id is generated now,
        // not deferred to flush, since toDto() below needs it immediately.
        orderItems.save(item)
        order.updatedAt = OffsetDateTime.now()
        return order.toDto(includeProfit, exchangeRates::toEur)
    }

    private fun applyItemContentEdit(item: OrderItem, request: UpdateOwnOrderItemRequest, enforceStatusGuard: Boolean) {
        if (enforceStatusGuard && item.status in LOCKED_ITEM_STATUSES) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "item has already been bought or shipped and can no longer be edited")
        }
        request.title?.let { item.customTitle = it }
        request.description?.let { item.customDescription = it }
        request.imageKey?.let {
            media.requireExisting(listOf(it))
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

    private fun removeItemFromOrder(order: Order, item: OrderItem, enforceStatusGuard: Boolean) {
        if (enforceStatusGuard && item.status in LOCKED_ITEM_STATUSES) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "item has already been bought or shipped and can no longer be removed")
        }
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

    private fun ownItemOrThrow(order: Order, itemId: UUID): OrderItem {
        val item = orderItems.findById(itemId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "order item not found") }
        if (item.order.id != order.id) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "order item not found")
        }
        return item
    }

    private fun buildOrderItem(order: Order, request: CreateOrderItemRequest): OrderItem {
        if (request.inventoryItemId != null) {
            val inventoryItem = inventoryItems.findById(request.inventoryItemId)
                .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "inventory item not found") }
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

        if (request.title.isNullOrBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "each order item needs an inventoryItemId or a title")
        }
        if (request.condition == null) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "condition is required for a custom order item")
        }
        if (request.isPreorder && request.expectedReleaseDate == null) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "a preorder item must have an expected release date")
        }
        request.imageKey?.let { media.requireExisting(listOf(it)) }
        addRequestedItemToInventory(request)
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

    // A custom (non-catalog) request has nothing in Inventory yet - surface it there too, at zero
    // stock, so the next person browsing the catalog sees it exists and can request it the normal
    // way instead of retyping it. Zero stock already means "still requestable, just signals the
    // admin to go source it" (see the inventoryItemId branch above), so no new status is needed.
    private fun addRequestedItemToInventory(request: CreateOrderItemRequest) {
        val newItem = InventoryItem(
            title = requireNotNull(request.title),
            description = request.description,
            link = request.link,
            condition = requireNotNull(request.condition),
            priceAmount = request.maxPrice ?: java.math.BigDecimal.ZERO,
            currency = request.currency,
            stockQuantity = 0,
            isPreorder = request.isPreorder,
            expectedReleaseDate = request.expectedReleaseDate,
        )
        if (request.imageKey != null) {
            newItem.images.add(InventoryItemImage(inventoryItem = newItem, objectKey = request.imageKey))
        }
        inventoryItems.save(newItem)
    }

    private fun findOrThrow(id: UUID): Order =
        orders.findById(id).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "order not found") }
}
