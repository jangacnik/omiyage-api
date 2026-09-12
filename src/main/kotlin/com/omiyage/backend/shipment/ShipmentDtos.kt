package com.omiyage.backend.shipment

import com.omiyage.backend.order.OrderDto
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
    val destination: ShipmentDestination?,
    val shippingMethod: ShippingMethod?,
    val trackingNumber: String?,
    val notes: String?,
    val ownerUserId: UUID?,
    val totalWeightGrams: BigDecimal,
    // Both null until destination and shippingMethod are set and a matching admin-configured
    // rate covers the total weight - see ShippingRateService.priceFor.
    val shippingCostJpy: Int?,
    val estimatedDays: Int?,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
    val orders: List<OrderDto>,
)

fun Shipment.toDto(priceFor: (ShippingMethod, ShipmentDestination, Int) -> ShippingQuote?): ShipmentDto {
    val weightByOrder = orders.associateWith { it.totalWeightGrams() }
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

    val ordersWithShares = orders.map { order ->
        val weight = weightByOrder.getValue(order)
        val share = if (totalCostJpy != null && totalWeight > BigDecimal.ZERO) {
            BigDecimal(totalCostJpy).multiply(weight).divide(totalWeight, 0, RoundingMode.HALF_UP).toInt()
        } else {
            null
        }
        order.toDto().copy(orderWeightGrams = weight, shippingCostShareJpy = share)
    }
    // Per-order shares are rounded independently and can be off by a yen or two from the total -
    // fold that drift into the last order rather than let per-share display quietly not sum up.
    val reconciledOrders = if (totalCostJpy != null && ordersWithShares.isNotEmpty()) {
        val roundedSum = ordersWithShares.sumOf { it.shippingCostShareJpy ?: 0 }
        val drift = totalCostJpy - roundedSum
        ordersWithShares.mapIndexed { index, dto ->
            if (index == ordersWithShares.lastIndex) {
                dto.copy(shippingCostShareJpy = (dto.shippingCostShareJpy ?: 0) + drift)
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
        destination = destination,
        shippingMethod = shippingMethod,
        trackingNumber = trackingNumber,
        notes = notes,
        ownerUserId = ownerUserId,
        totalWeightGrams = totalWeight,
        shippingCostJpy = totalCostJpy,
        estimatedDays = quote?.estimatedDays,
        createdAt = createdAt,
        updatedAt = updatedAt,
        orders = reconciledOrders,
    )
}

data class CreateShipmentRequest(
    @field:NotEmpty val orderIds: List<UUID>,
    val notes: String? = null,
)

data class UpdateShipmentRequest(
    val status: ShipmentStatus? = null,
    val destination: ShipmentDestination? = null,
    val shippingMethod: ShippingMethod? = null,
    val trackingNumber: String? = null,
    val notes: String? = null,
)

data class AssignShipmentRequest(val shipmentId: UUID?)
