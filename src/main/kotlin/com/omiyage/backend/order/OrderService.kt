package com.omiyage.backend.order

import com.omiyage.backend.catalog.InventoryItemRepository
import com.omiyage.backend.security.MeishiPrincipal
import com.omiyage.backend.security.Role
import com.omiyage.backend.shipment.ShipmentRepository
import com.omiyage.backend.shipment.ShipmentStatus
import com.omiyage.backend.web.PageResponse
import com.omiyage.backend.web.toPageResponse
import org.springframework.data.domain.Pageable
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

@Service
class OrderService(
    private val orders: OrderRepository,
    private val orderItems: OrderItemRepository,
    private val inventoryItems: InventoryItemRepository,
    private val shipments: ShipmentRepository,
) {

    @Transactional
    fun create(principal: MeishiPrincipal, request: CreateOrderRequest): OrderDto = createFor(principal.id, request)

    @Transactional
    fun adminCreate(userId: UUID, request: CreateOrderRequest): OrderDto = createFor(userId, request, includeProfit = true)

    private fun createFor(userId: UUID, request: CreateOrderRequest, includeProfit: Boolean = false): OrderDto {
        val order = Order(userId = userId, notes = request.notes, nickname = request.nickname?.trim()?.ifBlank { null })
        request.items.forEach { order.items.add(buildOrderItem(order, it)) }
        return orders.save(order).toDto(includeProfit)
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
        return order.toDto(includeProfit = isAdmin)
    }

    @Transactional(readOnly = true)
    fun adminList(status: OrderStatus?, pageable: Pageable): PageResponse<OrderDto> =
        (if (status == null) orders.findAll(pageable) else orders.findByStatus(status, pageable))
            .toPageResponse { it.toDto(includeProfit = true) }

    @Transactional
    fun updateStatus(id: UUID, status: OrderStatus): OrderDto {
        val order = findOrThrow(id)
        if (status == OrderStatus.CANCELLED) {
            cancelAndDetach(order)
        } else {
            order.status = status
            order.updatedAt = OffsetDateTime.now()
        }
        return order.toDto(includeProfit = true)
    }

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
        item.status = status
        if (status == OrderItemStatus.BOUGHT && item.purchasedAt == null) {
            item.purchasedAt = OffsetDateTime.now()
        }
        item.actualPrice = actualPrice
        item.soldPrice = soldPrice
        item.weight = weight
        item.updatedAt = OffsetDateTime.now()
        return item.order.toDto(includeProfit = true)
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
        return order.toDto(includeProfit = true)
    }

    @Transactional
    fun adminMarkItemPaid(orderId: UUID, itemId: UUID, paid: Boolean): OrderDto {
        val order = findOrThrow(orderId)
        val item = ownItemOrThrow(order, itemId)
        item.paid = paid
        item.paidAt = if (paid) OffsetDateTime.now() else null
        item.updatedAt = OffsetDateTime.now()
        return order.toDto(includeProfit = true)
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
        return order.toDto(includeProfit = true)
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
        return SplitOrderResponseDto(original = source.toDto(), created = created.toDto())
    }

    // Only the order's own owner (no shipment yet) or the shipment's owner (set when orders were
    // combined - see ShipmentService.create) may submit it. Submitting flips every still-open
    // order on the shipment to PREPARING_SHIPPING, not just the one the caller named, since the
    // shipment moves as a unit even if it holds other users' orders too.
    @Transactional
    fun submitForShipping(orderId: UUID, principal: MeishiPrincipal): OrderDto {
        val order = findOrThrow(orderId)
        val shipment = order.shipment
        // A shipment with no owner assigned authorizes nobody - it must never fall back to "the
        // order named in the URL happens to be this caller's", or any co-member of a shipment
        // could submit it by naming their own order, bypassing the owner-only rule entirely.
        val authorizedUserId = if (shipment == null) order.userId else shipment.ownerUserId
        if (authorizedUserId == null || authorizedUserId != principal.id) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "order not found")
        }
        requireOpen(order)
        val targets = shipment?.orders?.filter { it.isOpen } ?: listOf(order)
        targets.forEach { target ->
            target.status = OrderStatus.PREPARING_SHIPPING
            target.updatedAt = OffsetDateTime.now()
        }
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
        val distinctIds = orderIds.distinct()
        if (distinctIds.size < 2) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "select at least two different orders to merge")
        }

        val selected = distinctIds.map { findOrThrow(it) }
        selected.forEach { order ->
            if (order.userId != principal.id) {
                throw ResponseStatusException(HttpStatus.NOT_FOUND, "order not found")
            }
            requireOpen(order)
            val shipmentStatus = order.shipment?.status
            if (shipmentStatus != null && shipmentStatus != ShipmentStatus.PREPARING) {
                throw ResponseStatusException(HttpStatus.CONFLICT, "an order that has already shipped cannot be merged")
            }
        }

        val target = selected.first()
        val sources = selected.drop(1)
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
        return target.toDto()
    }

    @Transactional
    fun updateNickname(orderId: UUID, principal: MeishiPrincipal, nickname: String?): OrderDto {
        val order = findOrThrow(orderId)
        if (order.userId != principal.id) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "order not found")
        }
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

    private fun addItemToOrder(order: Order, request: CreateOrderItemRequest, includeProfit: Boolean = false): OrderDto {
        val item = buildOrderItem(order, request)
        order.items.add(item)
        // order is already persistent here (unlike create(), which persists a brand-new Order and
        // gets cascaded inserts for free) - save explicitly so the new item's id is generated now,
        // not deferred to flush, since toDto() below needs it immediately.
        orderItems.save(item)
        order.updatedAt = OffsetDateTime.now()
        return order.toDto(includeProfit)
    }

    private fun applyItemContentEdit(item: OrderItem, request: UpdateOwnOrderItemRequest, enforceStatusGuard: Boolean) {
        if (enforceStatusGuard && item.status in LOCKED_ITEM_STATUSES) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "item has already been bought or shipped and can no longer be edited")
        }
        request.title?.let { item.customTitle = it }
        request.description?.let { item.customDescription = it }
        request.imageKey?.let { item.customImageKey = it }
        request.maxPrice?.let { item.maxPrice = it }
        request.quantity?.let { newQuantity ->
            item.inventoryItem?.let { inv -> inv.stockQuantity = (inv.stockQuantity + item.quantity - newQuantity).coerceAtLeast(0) }
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
    // nothing was actually taken, so give the stock back (never below what was actually reserved).
    private fun restockIfLinked(item: OrderItem) {
        item.inventoryItem?.let { it.stockQuantity += item.quantity }
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
            inventoryItem.stockQuantity = (inventoryItem.stockQuantity - request.quantity).coerceAtLeast(0)
            return OrderItem(
                order = order,
                inventoryItem = inventoryItem,
                currency = request.currency,
                maxPrice = request.maxPrice,
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

    private fun findOrThrow(id: UUID): Order =
        orders.findById(id).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "order not found") }
}
