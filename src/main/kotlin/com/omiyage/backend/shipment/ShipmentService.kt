package com.omiyage.backend.shipment

import com.omiyage.backend.order.Order
import com.omiyage.backend.order.OrderDto
import com.omiyage.backend.order.OrderItemStatus
import com.omiyage.backend.order.OrderRepository
import com.omiyage.backend.order.OrderStatus
import com.omiyage.backend.order.isOpen
import com.omiyage.backend.order.markItemsPaid
import com.omiyage.backend.order.toDto
import com.omiyage.backend.security.MeishiPrincipal
import com.omiyage.backend.user.MeishiNicknameClient
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
    private val meishiNicknames: MeishiNicknameClient,
) {

    @Transactional(readOnly = true)
    fun listAll(): List<ShipmentDto> = shipments.findAll().map { it.toDto(shippingRates::priceFor, exchangeRates::toEurWithMarkup, exchangeRates::toEur) }

    @Transactional(readOnly = true)
    fun getById(id: UUID): ShipmentDto = findOrThrow(id).toDto(shippingRates::priceFor, exchangeRates::toEurWithMarkup, exchangeRates::toEur)

    // Shipments a member could ask to combine into: still PREPARING, and not already holding one
    // of their own orders. Owner nickname is a display nicety, not core data - degrade to null
    // rather than fail the whole listing if Meishi is unreachable, same as the admin orders list.
    @Transactional(readOnly = true)
    fun browsableForMember(userId: UUID, authHeader: String): List<BrowsableShipmentDto> {
        val nicknameByUserId = try {
            meishiNicknames.nicknames(authHeader).associate { it.id to it.nickname }
        } catch (e: Exception) {
            emptyMap()
        }
        return shipments.findAll()
            .filter { it.status == ShipmentStatus.PREPARING }
            .filter { shipment -> shipment.orders.none { it.userId == userId } }
            .map { shipment -> shipment.toBrowsableDto(shipment.ownerUserId?.let { nicknameByUserId[it] }) }
    }

    // Combining orders never spins up a fresh shipment when one isn't needed: the first selected
    // order "absorbs" the rest - if it already has a shipment, the others fold into that one; if
    // not, a new shipment is created and that order becomes its owner. Any other shipment a
    // selected order gets moved off of is deleted once empty, so two pre-existing shipments being
    // combined end up as one, not three.
    @Transactional
    fun create(request: CreateShipmentRequest): ShipmentDto {
        val shipment = combine(request.orderIds)
        request.notes?.let { shipment.notes = it }
        request.title?.let { shipment.title = it }
        return shipment.toDto(shippingRates::priceFor, exchangeRates::toEurWithMarkup, exchangeRates::toEur)
    }

    /** Shared combine primitive: folds every listed order onto the first one's shipment (creating
     *  one if it has none), reused by admin create(), member combine-request accept, and
     *  combineShipments() below. */
    @Transactional
    fun combine(orderIds: List<UUID>): Shipment {
        val distinctIds = orderIds.distinct()
        if (distinctIds.isEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "select at least one order to combine")
        }
        val selected = distinctIds.map { orderOrThrow(it) }
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
        val shipment = absorbing.shipment ?: shipments.save(Shipment(ownerUserId = absorbing.userId))
        if (shipment.ownerUserId == null) {
            shipment.ownerUserId = absorbing.userId
        }

        val vacated = mutableSetOf<UUID>()
        selected.forEach { order -> attach(shipment, order, vacated) }
        deleteIfEmpty(vacated - shipment.id!!)
        return shipment
    }

    // Flattens every order across the given shipments and runs them through the same combine()
    // primitive - works for any number of shipments holding any number of users' orders.
    @Transactional
    fun combineShipments(shipmentIds: List<UUID>, title: String?): ShipmentDto {
        val distinctIds = shipmentIds.distinct()
        if (distinctIds.size < 2) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "select at least two shipments to combine")
        }
        val selected = distinctIds.map { findOrThrow(it) }
        selected.forEach { shipment ->
            if (shipment.status != ShipmentStatus.PREPARING) {
                throw ResponseStatusException(HttpStatus.CONFLICT, "a shipment that has already shipped cannot be combined")
            }
        }
        val orderIds = selected.flatMap { it.orders.map { order -> requireNotNull(order.id) } }
        val combined = combine(orderIds)
        title?.let { combined.title = it }
        return combined.toDto(shippingRates::priceFor, exchangeRates::toEurWithMarkup, exchangeRates::toEur)
    }

    // Moves a chosen subset of a shipment's orders onto a brand-new shipment - the inverse of
    // combine(). At least one order must stay behind, otherwise this is just a rename, not a split.
    @Transactional
    fun splitOff(sourceShipmentId: UUID, orderIds: List<UUID>, title: String?): ShipmentDto {
        val source = findOrThrow(sourceShipmentId)
        if (source.status != ShipmentStatus.PREPARING) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "a shipment that has already shipped cannot be split")
        }
        val distinctIds = orderIds.distinct()
        val toMove = distinctIds.map { id ->
            source.orders.find { it.id == id }
                ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "order $id does not belong to this shipment")
        }
        if (toMove.isEmpty() || toMove.size == source.orders.size) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "split must move at least one order and leave at least one behind")
        }

        val destination = shipments.save(Shipment(ownerUserId = toMove.first().userId, title = title))
        val vacated = mutableSetOf<UUID>()
        toMove.forEach { order -> attach(destination, order, vacated) }
        deleteIfEmpty(vacated - destination.id!!)
        return destination.toDto(shippingRates::priceFor, exchangeRates::toEurWithMarkup, exchangeRates::toEur)
    }

    @Transactional
    fun update(id: UUID, request: UpdateShipmentRequest): ShipmentDto {
        val shipment = findOrThrow(id)
        request.status?.let { newStatus ->
            applyStatusChange(shipment, newStatus)
        }
        request.destination?.let { shipment.destination = it }
        request.shippingMethod?.let { shipment.shippingMethod = it }
        request.trackingNumber?.let { shipment.trackingNumber = it }
        request.notes?.let { shipment.notes = it }
        request.title?.let { shipment.title = it }
        shipment.updatedAt = OffsetDateTime.now()
        return shipment.toDto(shippingRates::priceFor, exchangeRates::toEurWithMarkup, exchangeRates::toEur)
    }

    // Owner-only self-service rename - admins use update() above instead, which covers every field.
    @Transactional
    fun renameMine(id: UUID, userId: UUID, title: String?): ShipmentDto {
        val shipment = findOrThrow(id)
        if (shipment.ownerUserId != userId) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "only the shipment owner can rename it")
        }
        shipment.title = title
        shipment.updatedAt = OffsetDateTime.now()
        return shipment.toDto(shippingRates::priceFor, exchangeRates::toEurWithMarkup, exchangeRates::toEur)
    }

    // Owner-only self-service "it arrived" - members can't set SHIPPED (that needs tracking/admin
    // confirmation) but marking a shipment that's already on its way as delivered doesn't need
    // admin involvement.
    @Transactional
    fun markArrivedMine(id: UUID, userId: UUID): ShipmentDto {
        val shipment = findOrThrow(id)
        if (shipment.ownerUserId != userId) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "only the shipment owner can mark it as arrived")
        }
        if (shipment.status != ShipmentStatus.SHIPPED) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "only a shipped shipment can be marked as arrived")
        }
        applyStatusChange(shipment, ShipmentStatus.DELIVERED)
        shipment.updatedAt = OffsetDateTime.now()
        return shipment.toDto(shippingRates::priceFor, exchangeRates::toEurWithMarkup, exchangeRates::toEur)
    }

    // Self-service detach: a member can pull their own order back off a shipment while it's still
    // being prepared - the same underlying move as assignOrder(orderId, null), just ownership-
    // checked instead of admin-trusted.
    @Transactional
    fun leaveShipment(orderId: UUID, principal: MeishiPrincipal): OrderDto {
        val order = orderOrThrow(orderId)
        if (order.userId != principal.id) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "order not found")
        }
        val shipment = order.shipment ?: throw ResponseStatusException(HttpStatus.CONFLICT, "order is not part of a shipment")
        if (shipment.status != ShipmentStatus.PREPARING) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "cannot leave a shipment that has already shipped")
        }
        return assignOrder(orderId, null)
    }

    private fun applyStatusChange(shipment: Shipment, newStatus: ShipmentStatus) {
        if (newStatus in setOf(ShipmentStatus.SHIPPED, ShipmentStatus.DELIVERED) &&
            shipment.orders.any { it.status !in READY_FOR_SHIPPING_STATUSES }
        ) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "every order in this shipment must reach preparing-shipping before it can ship")
        }
        if (newStatus == ShipmentStatus.SHIPPED && shipment.shippedAt == null) {
            shipment.shippedAt = OffsetDateTime.now()
            markOrdersSent(shipment)
        }
        if (newStatus == ShipmentStatus.DELIVERED && shipment.arrivedAt == null) {
            shipment.arrivedAt = OffsetDateTime.now()
        }
        shipment.status = newStatus
    }

    // Shipping physically moves every non-cancelled member order (and its items) on: the guard
    // above already required each to be at PREPARING_SHIPPING or later, so this is what "later"
    // means. A cancelled order never actually ships, so it's left exactly as it was.
    private fun markOrdersSent(shipment: Shipment) {
        val now = OffsetDateTime.now()
        shipment.orders.forEach { order ->
            if (order.status == OrderStatus.CANCELLED) return@forEach
            order.status = OrderStatus.SENT
            order.updatedAt = now
            order.items.forEach { item ->
                item.status = OrderItemStatus.SHIPPED
                item.updatedAt = now
            }
        }
    }

    @Transactional
    fun adminMarkPaid(id: UUID, paid: Boolean): ShipmentDto {
        val shipment = findOrThrow(id)
        shipment.orders.forEach { order -> markItemsPaid(order.items, paid) }
        return shipment.toDto(shippingRates::priceFor, exchangeRates::toEurWithMarkup, exchangeRates::toEur)
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
            // Same staleness concern as the destination side below, in reverse: if the previous
            // shipment's collection is already loaded in this session, leaving the order in it
            // would make that shipment look non-empty to any in-memory read for the rest of this
            // transaction (splitOff's own source shipment, for instance).
            order.shipment?.orders?.remove(order)
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
