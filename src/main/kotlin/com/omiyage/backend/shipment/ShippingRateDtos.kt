package com.omiyage.backend.shipment

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull
import java.util.UUID

data class ShippingRateDto(
    val id: UUID,
    val destination: ShipmentDestination,
    val shippingMethod: ShippingMethod,
    val maxWeightGrams: Int,
    val costJpy: Int,
    val estimatedDays: Int,
)

fun ShippingRate.toDto() = ShippingRateDto(
    id = requireNotNull(id),
    destination = destination,
    shippingMethod = shippingMethod,
    maxWeightGrams = maxWeightGrams,
    costJpy = costJpy,
    estimatedDays = estimatedDays,
)

data class ShippingRateRequest(
    @field:NotNull val destination: ShipmentDestination,
    @field:NotNull val shippingMethod: ShippingMethod,
    @field:Min(1) val maxWeightGrams: Int,
    @field:Min(0) val costJpy: Int,
    @field:Min(0) val estimatedDays: Int,
)
