package com.omiyage.backend.shipment

import com.omiyage.backend.order.OrderDto
import com.omiyage.backend.order.OrderRepository
import com.omiyage.backend.order.toDto
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.OffsetDateTime
import java.util.UUID

@Service
class ShipmentService(
    private val shipments: ShipmentRepository,
    private val orders: OrderRepository,
) {

    @Transactional(readOnly = true)
    fun listAll(): List<ShipmentDto> = shipments.findAll().map { it.toDto() }

    @Transactional(readOnly = true)
    fun getById(id: UUID): ShipmentDto = findOrThrow(id).toDto()

    @Transactional
    fun create(request: CreateShipmentRequest): ShipmentDto {
        val shipment = shipments.save(Shipment(notes = request.notes))
        request.orderIds.forEach { orderId -> attach(shipment, orderId) }
        return shipment.toDto()
    }

    @Transactional
    fun update(id: UUID, request: UpdateShipmentRequest): ShipmentDto {
        val shipment = findOrThrow(id)
        request.status?.let { shipment.status = it }
        request.trackingNumber?.let { shipment.trackingNumber = it }
        request.notes?.let { shipment.notes = it }
        shipment.updatedAt = OffsetDateTime.now()
        return shipment.toDto()
    }

    /** Attaches or detaches a single order to/from a shipment. Pass shipmentId = null to detach. */
    @Transactional
    fun assignOrder(orderId: UUID, shipmentId: UUID?): OrderDto {
        val order = orders.findById(orderId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "order not found") }
        order.shipment = shipmentId?.let { findOrThrow(it) }
        order.updatedAt = OffsetDateTime.now()
        return order.toDto()
    }

    private fun attach(shipment: Shipment, orderId: UUID) {
        val order = orders.findById(orderId)
            .orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "order not found: $orderId") }
        order.shipment = shipment
        order.updatedAt = OffsetDateTime.now()
    }

    private fun findOrThrow(id: UUID): Shipment =
        shipments.findById(id).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "shipment not found") }
}
