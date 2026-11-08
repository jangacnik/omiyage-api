package com.omiyage.backend.shipment

import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import com.omiyage.backend.errors.DomainError
import com.omiyage.backend.errors.OrderError
import com.omiyage.backend.errors.ShipmentError
import com.omiyage.backend.common.transactionalEither
import com.omiyage.backend.order.Order
import com.omiyage.backend.order.OrderDto
import com.omiyage.backend.order.OrderEventPublisher
import com.omiyage.backend.order.OrderItemStatus
import com.omiyage.backend.order.OrderRepository
import com.omiyage.backend.order.OrderStatus
import com.omiyage.backend.order.isOpen
import com.omiyage.backend.order.markItemsPaid
import com.omiyage.backend.order.toDto
import com.omiyage.backend.security.MeishiPrincipal
import com.omiyage.backend.user.MeishiNicknameClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.jvm.optionals.getOrNull

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
    private val orderEvents: OrderEventPublisher,
) {

    private val events = org.slf4j.LoggerFactory.getLogger("events")

    @Transactional(readOnly = true)
    fun listAll(): List<ShipmentDto> = shipments.findAll().map { it.toDto(shippingRates::priceFor, exchangeRates::toEurWithMarkup, exchangeRates::toEur) }

    // The admin shipments page's default view - delivered shipments are settled history, fetched
    // separately (listDelivered) only when an admin actually asks to see them.
    @Transactional(readOnly = true)
    fun listActive(): List<ShipmentDto> =
        shipments.findByStatusNot(ShipmentStatus.DELIVERED)
            .map { it.toDto(shippingRates::priceFor, exchangeRates::toEurWithMarkup, exchangeRates::toEur) }

    @Transactional(readOnly = true)
    fun listDelivered(): List<ShipmentDto> =
        shipments.findByStatus(ShipmentStatus.DELIVERED)
            .map { it.toDto(shippingRates::priceFor, exchangeRates::toEurWithMarkup, exchangeRates::toEur) }

    @Transactional(readOnly = true)
    fun getById(id: UUID): Either<DomainError, ShipmentDto> = either {
        findOrRaise(id).toDto(shippingRates::priceFor, exchangeRates::toEurWithMarkup, exchangeRates::toEur)
    }

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
        return shipments.findByStatus(ShipmentStatus.PREPARING)
            .filter { shipment -> shipment.orders.none { it.userId == userId } }
            .map { shipment -> shipment.toBrowsableDto(shipment.ownerUserId?.let { nicknameByUserId[it] }) }
    }

    // Combining orders never spins up a fresh shipment when one isn't needed: the first selected
    // order "absorbs" the rest - if it already has a shipment, the others fold into that one; if
    // not, a new shipment is created and that order becomes its owner. Any other shipment a
    // selected order gets moved off of is deleted once empty, so two pre-existing shipments being
    // combined end up as one, not three.
    @Transactional
    fun create(request: CreateShipmentRequest): Either<DomainError, ShipmentDto> = transactionalEither {
        val shipment = combine(request.orderIds).bind()
        request.notes?.let { shipment.notes = it }
        request.title?.let { shipment.title = it }
        shipment.toDto(shippingRates::priceFor, exchangeRates::toEurWithMarkup, exchangeRates::toEur)
    }

    /** Shared combine primitive: folds every listed order onto the first one's shipment (creating
     *  one if it has none), reused by admin create(), member combine-request accept, and
     *  combineShipments() below. */
    @Transactional
    fun combine(orderIds: List<UUID>): Either<DomainError, Shipment> = transactionalEither {
        val distinctIds = orderIds.distinct()
        ensure(distinctIds.isNotEmpty()) { ShipmentError.CombineNeedsOrders }
        val selected = distinctIds.map { orderOrRaise(it) }
        selected.forEach { order ->
            ensure(order.isOpen) { ShipmentError.OrderNotOpenForShipment }
            val shipmentStatus = order.shipment?.status
            ensure(shipmentStatus == null || shipmentStatus == ShipmentStatus.PREPARING) { ShipmentError.OrderShippedForShipment }
        }

        val absorbing = selected.first()
        val shipment = absorbing.shipment ?: shipments.save(Shipment(ownerUserId = absorbing.userId))
        if (shipment.ownerUserId == null) {
            shipment.ownerUserId = absorbing.userId
        }

        val vacated = mutableSetOf<UUID>()
        selected.forEach { order -> attach(shipment, order, vacated) }
        deleteIfEmpty(vacated - shipment.id!!)
        events.info("shipment_combined shipmentId={} orderCount={}", shipment.id, selected.size)
        shipment
    }

    // Flattens every order across the given shipments and runs them through the same combine()
    // primitive - works for any number of shipments holding any number of users' orders.
    @Transactional
    fun combineShipments(shipmentIds: List<UUID>, title: String?): Either<DomainError, ShipmentDto> = transactionalEither {
        val distinctIds = shipmentIds.distinct()
        ensure(distinctIds.size >= 2) { ShipmentError.CombineNeedsTwoShipments }
        val selected = distinctIds.map { findOrRaise(it) }
        selected.forEach { shipment ->
            ensure(shipment.status == ShipmentStatus.PREPARING) { ShipmentError.ShipmentShippedCannotCombine }
        }
        val orderIds = selected.flatMap { it.orders.map { order -> requireNotNull(order.id) } }
        val combined = combine(orderIds).bind()
        title?.let { combined.title = it }
        combined.toDto(shippingRates::priceFor, exchangeRates::toEurWithMarkup, exchangeRates::toEur)
    }

    // Moves a chosen subset of a shipment's orders onto a brand-new shipment - the inverse of
    // combine(). At least one order must stay behind, otherwise this is just a rename, not a split.
    @Transactional
    fun splitOff(sourceShipmentId: UUID, orderIds: List<UUID>, title: String?): Either<DomainError, ShipmentDto> = transactionalEither {
        val source = findOrRaise(sourceShipmentId)
        ensure(source.status == ShipmentStatus.PREPARING) { ShipmentError.ShipmentShippedCannotSplit }
        val distinctIds = orderIds.distinct()
        val toMove = distinctIds.map { id ->
            ensureNotNull(source.orders.find { it.id == id }) { ShipmentError.OrderNotInShipment(id) }
        }
        ensure(toMove.isNotEmpty() && toMove.size != source.orders.size) { ShipmentError.SplitNeedsSomeButNotAllOrders }

        val destination = shipments.save(Shipment(ownerUserId = toMove.first().userId, title = title))
        events.info("shipment_created shipmentId={} sourceShipmentId={} orderCount={}", destination.id, sourceShipmentId, toMove.size)
        val vacated = mutableSetOf<UUID>()
        toMove.forEach { order -> attach(destination, order, vacated) }
        deleteIfEmpty(vacated - destination.id!!)
        destination.toDto(shippingRates::priceFor, exchangeRates::toEurWithMarkup, exchangeRates::toEur)
    }

    @Transactional
    fun update(id: UUID, request: UpdateShipmentRequest): Either<DomainError, ShipmentDto> = transactionalEither {
        val shipment = findOrRaise(id)
        request.status?.let { newStatus ->
            events.info("shipment_status_changed shipmentId={} from={} to={}", id, shipment.status, newStatus)
            applyStatusChange(shipment, newStatus)
        }
        request.destination?.let { shipment.destination = it }
        request.shippingMethod?.let { shipment.shippingMethod = it }
        request.trackingNumber?.let { shipment.trackingNumber = it }
        request.notes?.let { shipment.notes = it }
        request.title?.let { shipment.title = it }
        shipment.updatedAt = OffsetDateTime.now()
        shipment.toDto(shippingRates::priceFor, exchangeRates::toEurWithMarkup, exchangeRates::toEur)
    }

    // Owner-only self-service rename - admins use update() above instead, which covers every field.
    @Transactional
    fun renameMine(id: UUID, userId: UUID, title: String?): Either<DomainError, ShipmentDto> = transactionalEither {
        val shipment = findOrRaise(id)
        ensure(shipment.ownerUserId == userId) { ShipmentError.NotOwnerCannotRename }
        shipment.title = title
        shipment.updatedAt = OffsetDateTime.now()
        shipment.toDto(shippingRates::priceFor, exchangeRates::toEurWithMarkup, exchangeRates::toEur)
    }

    // Owner-only self-service destination/shipping method - only while still PREPARING, since
    // changing either after the courier has already been chosen and paid for doesn't mean
    // anything. Admins use update() above instead, which covers every field with no such guard.
    @Transactional
    fun updateShippingMine(
        id: UUID,
        userId: UUID,
        destination: ShipmentDestination?,
        shippingMethod: ShippingMethod?,
    ): Either<DomainError, ShipmentDto> = transactionalEither {
        val shipment = findOrRaise(id)
        ensure(shipment.ownerUserId == userId) { ShipmentError.NotOwnerCannotSetShipping }
        ensure(shipment.status == ShipmentStatus.PREPARING) { ShipmentError.ShippingLockedOnceShipped }
        destination?.let { shipment.destination = it }
        shippingMethod?.let { shipment.shippingMethod = it }
        shipment.updatedAt = OffsetDateTime.now()
        shipment.toDto(shippingRates::priceFor, exchangeRates::toEurWithMarkup, exchangeRates::toEur)
    }

    // Owner-only self-service "it arrived" - members can't set SHIPPED (that needs tracking/admin
    // confirmation) but marking a shipment that's already on its way as delivered doesn't need
    // admin involvement.
    @Transactional
    fun markArrivedMine(id: UUID, userId: UUID): Either<DomainError, ShipmentDto> = transactionalEither {
        val shipment = findOrRaise(id)
        ensure(shipment.ownerUserId == userId) { ShipmentError.NotOwnerCannotMarkArrived }
        ensure(shipment.status == ShipmentStatus.SHIPPED) { ShipmentError.OnlyShippedCanArrive }
        applyStatusChange(shipment, ShipmentStatus.DELIVERED)
        shipment.updatedAt = OffsetDateTime.now()
        shipment.toDto(shippingRates::priceFor, exchangeRates::toEurWithMarkup, exchangeRates::toEur)
    }

    // Self-service detach: a member can pull their own order back off a shipment while it's still
    // being prepared - the same underlying move as assignOrder(orderId, null), just ownership-
    // checked instead of admin-trusted.
    @Transactional
    fun leaveShipment(orderId: UUID, principal: MeishiPrincipal): Either<DomainError, OrderDto> = transactionalEither {
        val order = orderOrRaise(orderId)
        ensure(order.userId == principal.id) { OrderError.OrderNotFound }
        val shipment = ensureNotNull(order.shipment) { ShipmentError.OrderNotInShipmentToLeave }
        ensure(shipment.status == ShipmentStatus.PREPARING) { ShipmentError.LeaveAfterShipped }
        assignOrder(orderId, null).bind()
    }

    private fun Raise<DomainError>.applyStatusChange(shipment: Shipment, newStatus: ShipmentStatus) {
        ensure(
            newStatus !in setOf(ShipmentStatus.SHIPPED, ShipmentStatus.DELIVERED) ||
                shipment.orders.none { it.status !in READY_FOR_SHIPPING_STATUSES },
        ) { ShipmentError.OrdersNotReadyToShip }
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
    // means. A cancelled order never actually ships and a delivered one has already arrived, so both are left exactly as they were.
    private fun markOrdersSent(shipment: Shipment) {
        val now = OffsetDateTime.now()
        shipment.orders.forEach { order ->
            if (order.status == OrderStatus.CANCELLED || order.status == OrderStatus.DELIVERED) return@forEach
            val previousStatus = order.status
            order.status = OrderStatus.SENT
            order.updatedAt = now
            order.items.forEach { item ->
                item.status = OrderItemStatus.SHIPPED
                item.updatedAt = now
            }
            orderEvents.statusChanged(order, previousStatus)
        }
    }

    @Transactional
    fun adminMarkPaid(id: UUID, paid: Boolean): Either<DomainError, ShipmentDto> = transactionalEither {
        val shipment = findOrRaise(id)
        shipment.orders.forEach { order -> markItemsPaid(order.items, paid) }
        shipment.toDto(shippingRates::priceFor, exchangeRates::toEurWithMarkup, exchangeRates::toEur)
    }

    @Transactional
    fun adminDelete(id: UUID): Either<DomainError, Unit> = transactionalEither {
        ensure(shipments.existsById(id)) { ShipmentError.ShipmentNotFound }
        // Orders still pointing at this shipment would otherwise keep a dangling reference once
        // it's gone - same class of bug as a soft-deleted inventory item left attached to an
        // order item. Detach first, then delete.
        orders.detachShipment(id)
        shipments.deleteById(id)
    }

    /** Attaches or detaches a single order to/from a shipment. Pass shipmentId = null to detach. */
    @Transactional
    fun assignOrder(orderId: UUID, shipmentId: UUID?): Either<DomainError, OrderDto> = transactionalEither {
        val order = ensureNotNull(orders.findById(orderId).getOrNull()) { OrderError.OrderNotFound }
        val previousShipmentId = order.shipment?.id
        val target = shipmentId?.let { findOrRaise(it) }
        ensure(target == null || target.status == ShipmentStatus.PREPARING) { ShipmentError.AttachToShippedShipment }
        order.shipment?.orders?.remove(order)
        order.shipment = target
        target?.orders?.add(order)
        order.updatedAt = OffsetDateTime.now()
        val dto = order.toDto()
        if (previousShipmentId != null && previousShipmentId != shipmentId) {
            deleteIfEmpty(setOf(previousShipmentId))
        }
        dto
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

    private fun Raise<DomainError>.findOrRaise(id: UUID): Shipment =
        ensureNotNull(shipments.findById(id).getOrNull()) { ShipmentError.ShipmentNotFound }

    private fun Raise<DomainError>.orderOrRaise(id: UUID): Order =
        ensureNotNull(orders.findById(id).getOrNull()) { OrderError.OrderIdNotFound(id) }
}
