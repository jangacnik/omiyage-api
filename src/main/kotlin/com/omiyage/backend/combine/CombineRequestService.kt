package com.omiyage.backend.combine

import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import com.omiyage.backend.errors.CombineError
import com.omiyage.backend.errors.DomainError
import com.omiyage.backend.errors.OrderError
import com.omiyage.backend.errors.ShipmentError
import com.omiyage.backend.common.transactionalEither
import com.omiyage.backend.order.OrderRepository
import com.omiyage.backend.order.isOpen
import com.omiyage.backend.security.MeishiPrincipal
import com.omiyage.backend.shipment.ShipmentRepository
import com.omiyage.backend.shipment.ShipmentService
import com.omiyage.backend.shipment.ShipmentStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.jvm.optionals.getOrNull

// A requester sees their pending requests, and declined ones for 14 days so the decline is not silent.
private val DECLINED_VISIBLE_FOR = Duration.ofDays(14)

@Service
class CombineRequestService(
    private val requests: CombineRequestRepository,
    private val orders: OrderRepository,
    private val shipments: ShipmentRepository,
    private val shipmentService: ShipmentService,
) {

    private val events = org.slf4j.LoggerFactory.getLogger("events")

    @Transactional
    fun create(principal: MeishiPrincipal, requesterOrderId: UUID, targetShipmentId: UUID): Either<DomainError, CombineRequestDto> = transactionalEither {
        val requesterOrder = orderOrRaise(requesterOrderId)
        ensure(requesterOrder.userId == principal.id) { OrderError.OrderNotFound }
        val targetShipment = shipmentOrRaise(targetShipmentId)
        ensure(targetShipment.orders.none { it.userId == principal.id }) { CombineError.AlreadyOnShipment }
        requireOpenAndUnshipped(requesterOrder.isOpen, requesterOrder.shipment?.status)
        ensure(targetShipment.status == ShipmentStatus.PREPARING) { CombineError.TargetShipmentShipped }
        ensure(!requests.existsByRequesterOrder_IdAndTargetShipment_IdAndStatus(requesterOrderId, targetShipmentId, CombineRequestStatus.PENDING)) {
            CombineError.DuplicatePendingRequest
        }
        val saved = requests.save(CombineRequest(requesterOrder = requesterOrder, targetShipment = targetShipment))
        events.info("combine_request_created requestId={} orderId={} shipmentId={}", saved.id, requesterOrderId, targetShipmentId)
        saved.toDto()
    }

    @Transactional(readOnly = true)
    fun listIncoming(principal: MeishiPrincipal): List<CombineRequestDto> =
        requests.findByTargetShipment_OwnerUserIdAndStatus(principal.id, CombineRequestStatus.PENDING).map { it.toDto() }

    @Transactional(readOnly = true)
    fun listOutgoing(principal: MeishiPrincipal): List<CombineRequestDto> {
        val declinedAfter = OffsetDateTime.now().minus(DECLINED_VISIBLE_FOR)
        return requests.findOutgoing(principal.id, declinedAfter, CombineRequestStatus.PENDING, CombineRequestStatus.DECLINED).map { it.toDto() }
    }

    @Transactional(readOnly = true)
    fun listAllPending(): List<CombineRequestDto> = requests.findByStatus(CombineRequestStatus.PENDING).map { it.toDto() }

    @Transactional
    fun accept(principal: MeishiPrincipal, id: UUID): Either<DomainError, CombineRequestDto> = transactionalEither {
        val request = pendingOrRaise(id)
        ensure(request.targetShipment.ownerUserId == principal.id) { CombineError.RequestNotFound }
        doAccept(request)
    }

    @Transactional
    fun forceAccept(id: UUID): Either<DomainError, CombineRequestDto> = transactionalEither { doAccept(pendingOrRaise(id)) }

    @Transactional
    fun decline(principal: MeishiPrincipal, id: UUID): Either<DomainError, CombineRequestDto> = transactionalEither {
        val request = pendingOrRaise(id)
        ensure(request.targetShipment.ownerUserId == principal.id) { CombineError.RequestNotFound }
        request.status = CombineRequestStatus.DECLINED
        request.respondedAt = OffsetDateTime.now()
        events.info("combine_request_declined requestId={}", id)
        request.toDto()
    }

    @Transactional
    fun cancel(principal: MeishiPrincipal, id: UUID): Either<DomainError, CombineRequestDto> = transactionalEither {
        val request = pendingOrRaise(id)
        ensure(request.requesterOrder.userId == principal.id) { CombineError.RequestNotFound }
        request.status = CombineRequestStatus.CANCELLED
        request.respondedAt = OffsetDateTime.now()
        events.info("combine_request_cancelled requestId={}", id)
        request.toDto()
    }

    // Attaches exactly the requester's order onto the target shipment as-is - nothing else on
    // either side moves. Reuses assignOrder(), the same primitive the member-facing "leave
    // shipment" action uses, just in the attach direction.
    private fun Raise<DomainError>.doAccept(request: CombineRequest): CombineRequestDto {
        val requesterOrderId = requireNotNull(request.requesterOrder.id)
        val targetShipmentId = requireNotNull(request.targetShipment.id)
        val requesterOrder = orderOrRaise(requesterOrderId)
        requireOpenAndUnshipped(requesterOrder.isOpen, requesterOrder.shipment?.status)
        val targetShipment = shipmentOrRaise(targetShipmentId)
        ensure(targetShipment.status == ShipmentStatus.PREPARING) { CombineError.TargetShipmentShipped }
        // create() only checked this at request time - a second pending request for a different
        // order of the requester's, targeting the same shipment, would still have passed it back
        // then. Re-check here so accepting two such requests can't land the same member on the
        // same shipment twice.
        ensure(targetShipment.orders.none { it.userId == requesterOrder.userId }) { CombineError.MemberAlreadyOnShipment }
        shipmentService.assignOrder(requesterOrderId, targetShipmentId).bind()
        request.status = CombineRequestStatus.ACCEPTED
        request.respondedAt = OffsetDateTime.now()
        request.resultingShipmentId = targetShipmentId
        events.info("combine_request_accepted requestId={} shipmentId={}", request.id, targetShipmentId)
        return request.toDto()
    }

    private fun Raise<DomainError>.requireOpenAndUnshipped(isOpen: Boolean, shipmentStatus: ShipmentStatus?) {
        ensure(isOpen) { CombineError.OrderNotOpen }
        ensure(shipmentStatus == null || shipmentStatus == ShipmentStatus.PREPARING) { CombineError.OrderAlreadyShipped }
    }

    private fun Raise<DomainError>.pendingOrRaise(id: UUID): CombineRequest {
        val request = ensureNotNull(requests.findById(id).getOrNull()) { CombineError.RequestNotFound }
        ensure(request.status == CombineRequestStatus.PENDING) { CombineError.RequestNotPending }
        return request
    }

    private fun Raise<DomainError>.orderOrRaise(id: UUID) =
        ensureNotNull(orders.findById(id).getOrNull()) { OrderError.OrderNotFound }

    private fun Raise<DomainError>.shipmentOrRaise(id: UUID) =
        ensureNotNull(shipments.findById(id).getOrNull()) { ShipmentError.ShipmentNotFound }
}
