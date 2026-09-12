package com.omiyage.backend.shipment

import com.omiyage.backend.order.OrderDto
import com.omiyage.backend.order.toDto
import jakarta.validation.constraints.NotEmpty
import java.time.OffsetDateTime
import java.util.UUID

data class ShipmentDto(
    val id: UUID,
    val status: ShipmentStatus,
    val trackingNumber: String?,
    val notes: String?,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
    val orders: List<OrderDto>,
)

fun Shipment.toDto() = ShipmentDto(
    id = requireNotNull(id),
    status = status,
    trackingNumber = trackingNumber,
    notes = notes,
    createdAt = createdAt,
    updatedAt = updatedAt,
    orders = orders.map { it.toDto() },
)

data class CreateShipmentRequest(
    @field:NotEmpty val orderIds: List<UUID>,
    val notes: String? = null,
)

data class UpdateShipmentRequest(
    val status: ShipmentStatus? = null,
    val trackingNumber: String? = null,
    val notes: String? = null,
)

data class AssignShipmentRequest(val shipmentId: UUID?)
