package com.omiyage.backend.order

import com.omiyage.backend.catalog.InventoryItemRepository
import com.omiyage.backend.security.MeishiPrincipal
import com.omiyage.backend.security.Role
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

@Service
class OrderService(
    private val orders: OrderRepository,
    private val orderItems: OrderItemRepository,
    private val inventoryItems: InventoryItemRepository,
) {

    @Transactional
    fun create(principal: MeishiPrincipal, request: CreateOrderRequest): OrderDto {
        val order = Order(userId = principal.id, notes = request.notes)
        request.items.forEach { order.items.add(buildOrderItem(order, it)) }
        return orders.save(order).toDto()
    }

    @Transactional(readOnly = true)
    fun listMine(principal: MeishiPrincipal, pageable: Pageable): PageResponse<OrderDto> =
        orders.findByUserId(principal.id, pageable).toPageResponse { it.toDto() }

    @Transactional(readOnly = true)
    fun getById(id: UUID, principal: MeishiPrincipal): OrderDto {
        val order = findOrThrow(id)
        if (principal.role != Role.ADMIN && order.userId != principal.id) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "order not found")
        }
        return order.toDto()
    }

    @Transactional(readOnly = true)
    fun adminList(status: OrderStatus?, pageable: Pageable): PageResponse<OrderDto> =
        (if (status == null) orders.findAll(pageable) else orders.findByStatus(status, pageable))
            .toPageResponse { it.toDto() }

    @Transactional
    fun updateStatus(id: UUID, status: OrderStatus): OrderDto {
        val order = findOrThrow(id)
        order.status = status
        order.updatedAt = OffsetDateTime.now()
        return order.toDto()
    }

    @Transactional
    fun updateItem(
        orderId: UUID,
        itemId: UUID,
        status: OrderItemStatus,
        actualPrice: java.math.BigDecimal?,
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
        item.weight = weight
        item.updatedAt = OffsetDateTime.now()
        return item.order.toDto()
    }

    @Transactional
    fun updateOwnItem(orderId: UUID, itemId: UUID, principal: MeishiPrincipal, request: UpdateOwnOrderItemRequest): OrderDto {
        val order = findOrThrow(orderId)
        if (order.userId != principal.id) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "order not found")
        }
        val item = ownItemOrThrow(order, itemId)
        if (item.status == OrderItemStatus.BOUGHT) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "item has already been bought and can no longer be edited")
        }

        request.title?.let { item.customTitle = it }
        request.description?.let { item.customDescription = it }
        request.imageKey?.let { item.customImageKey = it }
        request.maxPrice?.let { item.maxPrice = it }
        request.quantity?.let { item.quantity = it }
        item.updatedAt = OffsetDateTime.now()
        return order.toDto()
    }

    @Transactional
    fun removeOwnItem(orderId: UUID, itemId: UUID, principal: MeishiPrincipal): OrderDto {
        val order = findOrThrow(orderId)
        if (order.userId != principal.id) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "order not found")
        }
        val item = ownItemOrThrow(order, itemId)
        if (item.status == OrderItemStatus.BOUGHT) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "item has already been bought and can no longer be removed")
        }

        order.items.remove(item)
        orderItems.delete(item)
        order.updatedAt = OffsetDateTime.now()
        return order.toDto()
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
            val shipmentStatus = order.shipment?.status
            if (shipmentStatus != null && shipmentStatus != ShipmentStatus.PREPARING) {
                throw ResponseStatusException(HttpStatus.CONFLICT, "an order that has already shipped cannot be merged")
            }
        }

        val target = selected.first()
        selected.drop(1).forEach { source ->
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
        orders.deleteAll(selected.drop(1))
        target.updatedAt = OffsetDateTime.now()
        return target.toDto()
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
            return OrderItem(
                order = order,
                inventoryItem = inventoryItem,
                currency = request.currency,
                maxPrice = request.maxPrice,
                quantity = request.quantity,
            )
        }

        if (request.title.isNullOrBlank()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "each order item needs an inventoryItemId or a title")
        }
        if (request.condition == null) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "condition is required for a custom order item")
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
        )
    }

    private fun findOrThrow(id: UUID): Order =
        orders.findById(id).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "order not found") }
}
