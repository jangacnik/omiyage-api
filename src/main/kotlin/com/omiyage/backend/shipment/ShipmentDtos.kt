package com.omiyage.backend.shipment

import com.omiyage.backend.order.OrderDto
import com.omiyage.backend.order.OrderStatus
import com.omiyage.backend.order.totalWeightGrams
import com.omiyage.backend.order.toDto
import jakarta.validation.constraints.NotEmpty
import java.math.BigDecimal
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
    toEurWithMarkup: (Int) -> BigDecimal,
    // Bare admin-set-rate conversion (ExchangeRateService.toEur, no shipping markup) - only used
    // for order items' JPY actualPrice -> EUR in profit(), a completely separate figure from the
    // shipping cost this function also converts.
    toEur: (BigDecimal) -> BigDecimal,
): ShipmentDto {
    // A cancelled order has nothing left to ship - it shouldn't count toward the shipment's
    // weight or be billed a cost share, even if it's still attached (e.g. leftover from before
    // cancelling started detaching orders automatically).
    val weightByOrder = orders.filter { it.status != OrderStatus.CANCELLED }.associateWith { it.totalWeightGrams() }
    val totalWeight = weightByOrder.values.fold(BigDecimal.ZERO) { sum, w -> sum + w }

    val quote = destination?.let { dest ->
        shippingMethod?.let { method ->
            if (totalWeight > BigDecimal.ZERO) {
                priceFor(method, dest, billedGrams(totalWeight))
            } else {
                null
            }
        }
    }
    val totalCostJpy = quote?.costJpy
    val totalCostEur = totalCostJpy?.let(toEurWithMarkup)

    val billableOrders = weightByOrder.keys.sortedBy { it.id }
    val billableWeights = billableOrders.map { weightByOrder.getValue(it) }
    val shareJpyByOrder = totalCostJpy?.let { cost ->
        billableOrders.zip(allocateByWeight(cost.toLong(), billableWeights).map { it.toInt() }).toMap()
    }.orEmpty()
    val shareEurByOrder = totalCostEur?.let { cost ->
        billableOrders.zip(allocateByWeight(cost.movePointRight(2).longValueExact(), billableWeights).map { BigDecimal.valueOf(it, 2) }).toMap()
    }.orEmpty()

    val reconciledOrders = orders.map { order ->
        order.toDto(includeProfit = true, toEur = toEur).copy(
            orderWeightGrams = weightByOrder[order],
            shippingCostShareJpy = shareJpyByOrder[order],
            shippingCostShareEur = shareEurByOrder[order],
        )
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
    @field:jakarta.validation.constraints.Size(max = 100) val title: String? = null,
)

data class UpdateShipmentRequest(
    val status: ShipmentStatus? = null,
    val destination: ShipmentDestination? = null,
    val shippingMethod: ShippingMethod? = null,
    val trackingNumber: String? = null,
    val notes: String? = null,
    @field:jakarta.validation.constraints.Size(max = 100) val title: String? = null,
)

data class AssignShipmentRequest(val shipmentId: UUID?)

data class RenameShipmentRequest(@field:jakarta.validation.constraints.Size(max = 100) val title: String? = null)

data class UpdateShippingRequest(val destination: ShipmentDestination? = null, val shippingMethod: ShippingMethod? = null)

data class CombineShipmentsRequest(@field:NotEmpty val shipmentIds: List<UUID>, @field:jakarta.validation.constraints.Size(max = 100) val title: String? = null)

data class SplitShipmentRequest(@field:NotEmpty val orderIds: List<UUID>, @field:jakarta.validation.constraints.Size(max = 100) val title: String? = null)
