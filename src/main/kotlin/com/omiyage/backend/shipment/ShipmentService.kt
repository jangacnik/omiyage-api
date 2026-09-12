package com.omiyage.backend.shipment

import com.omiyage.backend.order.Order
import com.omiyage.backend.order.OrderDto
import com.omiyage.backend.order.OrderRepository
import com.omiyage.backend.order.OrderStatus
import com.omiyage.backend.order.isOpen
import com.omiyage.backend.order.toDto
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.OffsetDateTime
import java.util.UUID

// A shipment past PREPARING is treated as locked - nothing may join or leave its order list, and
// it can't advance further until every member order (other than one that got cancelled along the
// way) has itself reached PREPARING_SHIPPING or later.
private val READY_FOR_SHIPPING_STATUSES = setOf(OrderStatus.PREPARING_SHIPPING, OrderStatus.SENT, OrderStatus.DELIVERED, OrderStatus.CANCELLED)

@Service
class ShipmentService(
    private val shipments: ShipmentRepository,
    private val orders: OrderRepository,
    private val shippingRates: ShippingRateService,
    private val exchangeRates: ExchangeRateService,
) {

    @Transactional(readOnly = true)
    fun listAll(): List<ShipmentDto> = shipments.findAll().map { it.toDto(shippingRates::priceFor, exchangeRates::toEurWithMarkup) }

    @Transactional(readOnly = true)
    fun getById(id: UUID): ShipmentDto = findOrThrow(id).toDto(shippingRates::priceFor, exchangeRates::toEurWithMarkup)

    // Combining orders never spins up a fresh shipment when one isn't needed: the first selected
    // order "absorbs" the rest - if it already has a shipment, the others fold into that one; if
    // not, a new shipment is created and that order becomes its owner. Any other shipment a
    // selected order gets moved off of is deleted once empty, so two pre-existing shipments being
    // combined end up as one, not three.
    @Transactional
    fun create(request: CreateShipmentRequest): ShipmentDto {
        val orderIds = request.orderIds.distinct()
        if (orderIds.isEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "select at least one order to combine")
        }
        val selected = orderIds.map { orderOrThrow(it) }
        selected.forEach { order ->
            if (!order.isOpen) {
                throw ResponseStatusException(HttpStatus.CONFLICT, "an order that is no longer open cannot be combined into a shipment")
            }
            val shipmentStatus = order.shipment?.status
            if (shipmentStatus != null && shipmentStatus != ShipmentStatus.PREPARING) {
                throw ResponseStatusException(HttpStatus.CONFLICT, "an order that has already shipped cannot be combined into another shipment")
            }
        }

        val absorbing = selected.first()
        val shipment = absorbing.shipment ?: shipments.save(Shipment(notes = request.notes, ownerUserId = absorbing.userId))
        if (shipment.ownerUserId == null) {
            shipment.ownerUserId = absorbing.userId
        }

        val vacated = mutableSetOf<UUID>()
        selected.forEach { order -> attach(shipment, order, vacated) }
        deleteIfEmpty(vacated - shipment.id!!)
        return shipment.toDto(shippingRates::priceFor, exchangeRates::toEurWithMarkup)
    }

    @Transactional
    fun update(id: UUID, request: UpdateShipmentRequest): ShipmentDto {
        val shipment = findOrThrow(id)
        request.status?.let { newStatus ->
            if (newStatus in setOf(ShipmentStatus.SHIPPED, ShipmentStatus.DELIVERED) &&
                shipment.orders.any { it.status !in READY_FOR_SHIPPING_STATUSES }
            ) {
                throw ResponseStatusException(HttpStatus.CONFLICT, "every order in this shipment must reach preparing-shipping before it can ship")
            }
            shipment.status = newStatus
        }
        request.destination?.let { shipment.destination = it }
        request.shippingMethod?.let { shipment.shippingMethod = it }
        request.trackingNumber?.let { shipment.trackingNumber = it }
        request.notes?.let { shipment.notes = it }
        shipment.updatedAt = OffsetDateTime.now()
        return shipment.toDto(shippingRates::priceFor, exchangeRates::toEurWithMarkup)
    }

    @Transactional
    fun adminDelete(id: UUID) {
        if (!shipments.existsById(id)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "shipment not found")
        }
        // Orders still pointing at this shipment would otherwise keep a dangling reference once
        // it's gone - same class of bug as a soft-deleted inventory item left attached to an
        // order item. Detach first, then delete.
        orders.detachShipment(id)
        shipments.deleteById(id)
    }

    /** Attaches or detaches a single order to/from a shipment. Pass shipmentId = null to detach. */
    @Transactional
    fun assignOrder(orderId: UUID, shipmentId: UUID?): OrderDto {
        val order = orders.findById(orderId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "order not found") }
        val previousShipmentId = order.shipment?.id
        val target = shipmentId?.let { findOrThrow(it) }
        if (target != null && target.status != ShipmentStatus.PREPARING) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "cannot attach an order to a shipment that has already shipped")
        }
        order.shipment = target
        order.updatedAt = OffsetDateTime.now()
        val dto = order.toDto()
        if (previousShipmentId != null && previousShipmentId != shipmentId) {
            deleteIfEmpty(setOf(previousShipmentId))
        }
        return dto
    }

    private fun attach(shipment: Shipment, order: Order, vacated: MutableSet<UUID>) {
        val previousShipmentId = order.shipment?.id
        if (previousShipmentId == shipment.id) {
            // Already attached here (e.g. the absorbing order combining into its own existing
            // shipment) - touching shipment.orders again would duplicate it in the in-memory list,
            // since the lazy collection load below would already contain this same entity.
            return
        }
        if (previousShipmentId != null) {
            vacated += previousShipmentId
        }
        order.shipment = shipment
        order.updatedAt = OffsetDateTime.now()
        // Setting the owning side (order.shipment) alone doesn't update this JPA session's
        // in-memory inverse collection - keep it in sync so create()'s immediate response
        // (read within the same transaction) reflects the attached orders, not a stale list.
        shipment.orders.add(order)
    }

    // An order moving off a shipment (combined elsewhere, or reassigned/detached) can leave that
    // shipment referenced by no order at all - delete it instead of leaving it empty.
    private fun deleteIfEmpty(shipmentIds: Set<UUID>) {
        shipmentIds.forEach { id ->
            if (!orders.existsByShipment_Id(id)) {
                shipments.deleteById(id)
            }
        }
    }

    private fun findOrThrow(id: UUID): Shipment =
        shipments.findById(id).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "shipment not found") }

    private fun orderOrThrow(id: UUID): Order =
        orders.findById(id).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "order not found: $id") }
}
