package com.omiyage.backend.shipment

import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull
import java.math.BigDecimal
import java.util.UUID

data class ShippingRateDto(
    val id: UUID,
    val destination: ShipmentDestination,
    val shippingMethod: ShippingMethod,
    val maxWeightGrams: Int,
    val costJpy: Int,
    // Already includes the admin-configured conversion rate plus the hidden shipping markup -
    // same figure a real shipment using this tier would show. Computed here (not just on
    // ShipmentDto) so the admin's own rate table can never be compared against a shipment's total
    // to reverse-engineer the markup.
    val costEur: BigDecimal,
    val estimatedDays: Int,
)

fun ShippingRate.toDto(toEur: (Int) -> BigDecimal) = ShippingRateDto(
    id = requireNotNull(id),
    destination = destination,
    shippingMethod = shippingMethod,
    maxWeightGrams = maxWeightGrams,
    costJpy = costJpy,
    costEur = toEur(costJpy),
    estimatedDays = estimatedDays,
)

data class ShippingRateRequest(
    @field:NotNull val destination: ShipmentDestination,
    @field:NotNull val shippingMethod: ShippingMethod,
    @field:Min(1) val maxWeightGrams: Int,
    @field:Min(0) val costJpy: Int,
    @field:Min(0) val estimatedDays: Int,
)
