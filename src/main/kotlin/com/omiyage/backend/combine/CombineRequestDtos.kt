package com.omiyage.backend.combine

import com.omiyage.backend.shipment.ShipmentSummaryDto
import com.omiyage.backend.shipment.toSummaryDto
import jakarta.validation.constraints.NotNull
import java.time.OffsetDateTime
import java.util.UUID

data class OrderSummaryDto(val id: UUID, val nickname: String?, val itemCount: Int)

data class CombineRequestDto(
    val id: UUID,
    val requesterOrder: OrderSummaryDto,
    val targetShipment: ShipmentSummaryDto,
    val status: CombineRequestStatus,
    val createdAt: OffsetDateTime,
    val respondedAt: OffsetDateTime?,
    val resultingShipmentId: UUID?,
)

fun CombineRequest.toDto() = CombineRequestDto(
    id = requireNotNull(id),
    requesterOrder = OrderSummaryDto(requireNotNull(requesterOrder.id), requesterOrder.nickname, requesterOrder.items.size),
    targetShipment = targetShipment.toSummaryDto(),
    status = status,
    createdAt = createdAt,
    respondedAt = respondedAt,
    resultingShipmentId = resultingShipmentId,
)

data class CreateCombineRequestRequest(@field:NotNull val requesterOrderId: UUID, @field:NotNull val targetShipmentId: UUID)
