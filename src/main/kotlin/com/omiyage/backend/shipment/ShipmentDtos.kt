package com.omiyage.backend.shipment

import com.omiyage.backend.order.OrderDto
import com.omiyage.backend.order.OrderStatus
import com.omiyage.backend.order.totalWeightGrams
import com.omiyage.backend.order.toDto
import jakarta.validation.constraints.NotEmpty
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.OffsetDateTime
import java.util.UUID

data class ShipmentDto(
    val id: UUID,
    val status: ShipmentStatus,
    val title: String?,
    val destination: ShipmentDestination?,
    val shippingMethod: ShippingMethod?,
    val trackingNumber: String?,
    val notes: String?,
    val ownerUserId: UUID?,
    val shippedAt: OffsetDateTime?,
    val arrivedAt: OffsetDateTime?,
    val totalWeightGrams: BigDecimal,
    // Both null until destination and shippingMethod are set and a matching admin-configured
    // rate covers the total weight - see ShippingRateService.priceFor.
    val shippingCostJpy: Int?,
    // EUR display amount already includes the admin-configured conversion rate plus the hidden
    // shipping markup - see ExchangeRateService.toEurWithMarkup. Never derive this on the
    // frontend; always show exactly what's sent here.
    val shippingCostEur: BigDecimal?,
    val estimatedDays: Int?,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
    val orders: List<OrderDto>,
    // Admin-only - sum of each order's profit. Every shipment endpoint is admin-only, so this is
    // always populated (unlike OrderDto.profit, which needs an explicit includeProfit opt-in).
    val profit: BigDecimal,
)

fun Shipment.toDto(
    priceFor: (ShippingMethod, ShipmentDestination, Int) -> ShippingQuote?,
    toEur: (Int) -> BigDecimal,
): ShipmentDto {
    // A cancelled order has nothing left to ship - it shouldn't count toward the shipment's
    // weight or be billed a cost share, even if it's still attached (e.g. leftover from before
    // cancelling started detaching orders automatically).
    val weightByOrder = orders.filter { it.status != OrderStatus.CANCELLED }.associateWith { it.totalWeightGrams() }
    val totalWeight = weightByOrder.values.fold(BigDecimal.ZERO) { sum, w -> sum + w }

    val quote = destination?.let { dest ->
        shippingMethod?.let { method ->
            if (totalWeight > BigDecimal.ZERO) {
                priceFor(method, dest, totalWeight.setScale(0, RoundingMode.CEILING).toInt())
            } else {
                null
            }
        }
    }
    val totalCostJpy = quote?.costJpy
    val totalCostEur = totalCostJpy?.let(toEur)

    val ordersWithShares = orders.map { order ->
        val weight = weightByOrder[order]
        val shareJpy = if (weight != null && totalCostJpy != null && totalWeight > BigDecimal.ZERO) {
            BigDecimal(totalCostJpy).multiply(weight).divide(totalWeight, 0, RoundingMode.HALF_UP).toInt()
        } else {
            null
        }
        order.toDto(includeProfit = true).copy(orderWeightGrams = weight, shippingCostShareJpy = shareJpy, shippingCostShareEur = shareJpy?.let(toEur))
    }
    // Per-order shares are rounded independently and can be off by a bit from the total in both
    // currencies - fold that drift into the last billable order (a cancelled one has no share).
    val lastBillableIndex = ordersWithShares.indexOfLast { it.shippingCostShareJpy != null }
    val reconciledOrders = if (totalCostJpy != null && lastBillableIndex >= 0) {
        val driftJpy = totalCostJpy - ordersWithShares.sumOf { it.shippingCostShareJpy ?: 0 }
        val roundedSumEur = ordersWithShares.fold(BigDecimal.ZERO) { sum, dto -> sum + (dto.shippingCostShareEur ?: BigDecimal.ZERO) }
        val driftEur = (totalCostEur ?: BigDecimal.ZERO) - roundedSumEur
        ordersWithShares.mapIndexed { index, dto ->
            if (index == lastBillableIndex) {
                dto.copy(
                    shippingCostShareJpy = (dto.shippingCostShareJpy ?: 0) + driftJpy,
                    shippingCostShareEur = (dto.shippingCostShareEur ?: BigDecimal.ZERO) + driftEur,
                )
            } else {
                dto
            }
        }
    } else {
        ordersWithShares
    }

    return ShipmentDto(
        id = requireNotNull(id),
        status = status,
        title = title,
        destination = destination,
        shippingMethod = shippingMethod,
        trackingNumber = trackingNumber,
        notes = notes,
        ownerUserId = ownerUserId,
        shippedAt = shippedAt,
        arrivedAt = arrivedAt,
        totalWeightGrams = totalWeight,
        shippingCostJpy = totalCostJpy,
        shippingCostEur = totalCostEur,
        estimatedDays = quote?.estimatedDays,
        createdAt = createdAt,
        updatedAt = updatedAt,
        orders = reconciledOrders,
        profit = reconciledOrders.mapNotNull { it.profit }.fold(BigDecimal.ZERO, BigDecimal::add),
    )
}

data class CreateShipmentRequest(
    @field:NotEmpty val orderIds: List<UUID>,
    val notes: String? = null,
    val title: String? = null,
)

data class UpdateShipmentRequest(
    val status: ShipmentStatus? = null,
    val destination: ShipmentDestination? = null,
    val shippingMethod: ShippingMethod? = null,
    val trackingNumber: String? = null,
    val notes: String? = null,
    val title: String? = null,
)

data class AssignShipmentRequest(val shipmentId: UUID?)

data class RenameShipmentRequest(@field:jakarta.validation.constraints.Size(max = 100) val title: String? = null)

data class CombineShipmentsRequest(@field:NotEmpty val shipmentIds: List<UUID>, val title: String? = null)

data class SplitShipmentRequest(@field:NotEmpty val orderIds: List<UUID>, val title: String? = null)
