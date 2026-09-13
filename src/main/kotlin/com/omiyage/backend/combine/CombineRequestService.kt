package com.omiyage.backend.combine

import com.omiyage.backend.order.OrderRepository
import com.omiyage.backend.order.isOpen
import com.omiyage.backend.security.MeishiPrincipal
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
    private val shipmentService: ShipmentService,
) {

    @Transactional
    fun create(principal: MeishiPrincipal, requesterOrderId: UUID, targetOrderId: UUID): CombineRequestDto {
        if (requesterOrderId == targetOrderId) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "an order cannot be combined with itself")
        }
        val requesterOrder = orderOrThrow(requesterOrderId)
        if (requesterOrder.userId != principal.id) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "order not found")
        }
        val targetOrder = orderOrThrow(targetOrderId)
        if (targetOrder.userId == principal.id) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "cannot send a combine request to your own order")
        }
        requireOpenAndUnshipped(requesterOrder.isOpen, requesterOrder.shipment?.status)
        requireOpenAndUnshipped(targetOrder.isOpen, targetOrder.shipment?.status)
        if (requests.existsByRequesterOrder_IdAndTargetOrder_IdAndStatus(requesterOrderId, targetOrderId, CombineRequestStatus.PENDING)) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "a pending combine request already exists for these orders")
        }
        return requests.save(CombineRequest(requesterOrder = requesterOrder, targetOrder = targetOrder)).toDto()
    }

    @Transactional(readOnly = true)
    fun listIncoming(principal: MeishiPrincipal): List<CombineRequestDto> =
        requests.findByTargetOrder_UserIdAndStatus(principal.id, CombineRequestStatus.PENDING).map { it.toDto() }

    @Transactional(readOnly = true)
    fun listOutgoing(principal: MeishiPrincipal): List<CombineRequestDto> =
        requests.findByRequesterOrder_UserIdAndStatus(principal.id, CombineRequestStatus.PENDING).map { it.toDto() }

    @Transactional(readOnly = true)
    fun listAllPending(): List<CombineRequestDto> = requests.findByStatus(CombineRequestStatus.PENDING).map { it.toDto() }

    @Transactional
    fun accept(principal: MeishiPrincipal, id: UUID): CombineRequestDto {
        val request = pendingOrThrow(id)
        if (request.targetOrder.userId != principal.id) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "combine request not found")
        }
        return doAccept(request)
    }

    @Transactional
    fun forceAccept(id: UUID): CombineRequestDto = doAccept(pendingOrThrow(id))

    @Transactional
    fun decline(principal: MeishiPrincipal, id: UUID): CombineRequestDto {
        val request = pendingOrThrow(id)
        if (request.targetOrder.userId != principal.id) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "combine request not found")
        }
        request.status = CombineRequestStatus.DECLINED
        request.respondedAt = OffsetDateTime.now()
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
        return request.toDto()
    }

    // Combines exactly the two orders this request names - nothing else, per product decision:
    // accepting only ever folds in the specific order that was asked for.
    private fun doAccept(request: CombineRequest): CombineRequestDto {
        val requesterOrderId = requireNotNull(request.requesterOrder.id)
        val targetOrderId = requireNotNull(request.targetOrder.id)
        val fresh = listOf(orderOrThrow(requesterOrderId), orderOrThrow(targetOrderId))
        fresh.forEach { requireOpenAndUnshipped(it.isOpen, it.shipment?.status) }
        val shipment = shipmentService.combine(listOf(requesterOrderId, targetOrderId))
        request.status = CombineRequestStatus.ACCEPTED
        request.respondedAt = OffsetDateTime.now()
        request.resultingShipmentId = shipment.id
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
}
