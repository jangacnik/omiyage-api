package com.omiyage.backend.combine

import com.omiyage.backend.order.OrderRepository
import com.omiyage.backend.order.isOpen
import com.omiyage.backend.security.MeishiPrincipal
import com.omiyage.backend.shipment.ShipmentRepository
import com.omiyage.backend.shipment.ShipmentService
import com.omiyage.backend.shipment.ShipmentStatus
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.OffsetDateTime
import java.util.UUID

@Service
class CombineRequestService(
    private val requests: CombineRequestRepository,
    private val orders: OrderRepository,
    private val shipments: ShipmentRepository,
    private val shipmentService: ShipmentService,
) {

    private val events = org.slf4j.LoggerFactory.getLogger("events")

    @Transactional
    fun create(principal: MeishiPrincipal, requesterOrderId: UUID, targetShipmentId: UUID): CombineRequestDto {
        val requesterOrder = orderOrThrow(requesterOrderId)
        if (requesterOrder.userId != principal.id) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "order not found")
        }
        val targetShipment = shipmentOrThrow(targetShipmentId)
        if (targetShipment.orders.any { it.userId == principal.id }) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "you already have an order on this shipment")
        }
        requireOpenAndUnshipped(requesterOrder.isOpen, requesterOrder.shipment?.status)
        if (targetShipment.status != ShipmentStatus.PREPARING) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "a shipment that has already shipped cannot be combined into")
        }
        if (requests.existsByRequesterOrder_IdAndTargetShipment_IdAndStatus(requesterOrderId, targetShipmentId, CombineRequestStatus.PENDING)) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "a pending combine request already exists for this order and shipment")
        }
        val saved = requests.save(CombineRequest(requesterOrder = requesterOrder, targetShipment = targetShipment))
        events.info("combine_request_created requestId={} orderId={} shipmentId={}", saved.id, requesterOrderId, targetShipmentId)
        return saved.toDto()
    }

    @Transactional(readOnly = true)
    fun listIncoming(principal: MeishiPrincipal): List<CombineRequestDto> =
        requests.findByTargetShipment_OwnerUserIdAndStatus(principal.id, CombineRequestStatus.PENDING).map { it.toDto() }

    @Transactional(readOnly = true)
    fun listOutgoing(principal: MeishiPrincipal): List<CombineRequestDto> =
        requests.findByRequesterOrder_UserIdAndStatus(principal.id, CombineRequestStatus.PENDING).map { it.toDto() }

    @Transactional(readOnly = true)
    fun listAllPending(): List<CombineRequestDto> = requests.findByStatus(CombineRequestStatus.PENDING).map { it.toDto() }

    @Transactional
    fun accept(principal: MeishiPrincipal, id: UUID): CombineRequestDto {
        val request = pendingOrThrow(id)
        if (request.targetShipment.ownerUserId != principal.id) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "combine request not found")
        }
        return doAccept(request)
    }

    @Transactional
    fun forceAccept(id: UUID): CombineRequestDto = doAccept(pendingOrThrow(id))

    @Transactional
    fun decline(principal: MeishiPrincipal, id: UUID): CombineRequestDto {
        val request = pendingOrThrow(id)
        if (request.targetShipment.ownerUserId != principal.id) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "combine request not found")
        }
        request.status = CombineRequestStatus.DECLINED
        request.respondedAt = OffsetDateTime.now()
        events.info("combine_request_declined requestId={}", id)
        return request.toDto()
    }

    @Transactional
    fun cancel(principal: MeishiPrincipal, id: UUID): CombineRequestDto {
        val request = pendingOrThrow(id)
        if (request.requesterOrder.userId != principal.id) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "combine request not found")
        }
        request.status = CombineRequestStatus.CANCELLED
        request.respondedAt = OffsetDateTime.now()
        events.info("combine_request_cancelled requestId={}", id)
        return request.toDto()
    }

    // Attaches exactly the requester's order onto the target shipment as-is - nothing else on
    // either side moves. Reuses assignOrder(), the same primitive the member-facing "leave
    // shipment" action uses, just in the attach direction.
    private fun doAccept(request: CombineRequest): CombineRequestDto {
        val requesterOrderId = requireNotNull(request.requesterOrder.id)
        val targetShipmentId = requireNotNull(request.targetShipment.id)
        val requesterOrder = orderOrThrow(requesterOrderId)
        requireOpenAndUnshipped(requesterOrder.isOpen, requesterOrder.shipment?.status)
        val targetShipment = shipmentOrThrow(targetShipmentId)
        if (targetShipment.status != ShipmentStatus.PREPARING) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "a shipment that has already shipped cannot be combined into")
        }
        // create() only checked this at request time - a second pending request for a different
        // order of the requester's, targeting the same shipment, would still have passed it back
        // then. Re-check here so accepting two such requests can't land the same member on the
        // same shipment twice.
        if (targetShipment.orders.any { it.userId == requesterOrder.userId }) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "that member already has an order on this shipment")
        }
        shipmentService.assignOrder(requesterOrderId, targetShipmentId)
        request.status = CombineRequestStatus.ACCEPTED
        request.respondedAt = OffsetDateTime.now()
        request.resultingShipmentId = targetShipmentId
        events.info("combine_request_accepted requestId={} shipmentId={}", request.id, targetShipmentId)
        return request.toDto()
    }

    private fun requireOpenAndUnshipped(isOpen: Boolean, shipmentStatus: ShipmentStatus?) {
        if (!isOpen) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "an order that is no longer open cannot be combined")
        }
        if (shipmentStatus != null && shipmentStatus != ShipmentStatus.PREPARING) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "an order that has already shipped cannot be combined")
        }
    }

    private fun pendingOrThrow(id: UUID): CombineRequest {
        val request = requests.findById(id).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "combine request not found") }
        if (request.status != CombineRequestStatus.PENDING) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "combine request is no longer pending")
        }
        return request
    }

    private fun orderOrThrow(id: UUID) =
        orders.findById(id).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "order not found") }

    private fun shipmentOrThrow(id: UUID) =
        shipments.findById(id).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "shipment not found") }
}
