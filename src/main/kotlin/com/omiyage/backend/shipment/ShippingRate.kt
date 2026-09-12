package com.omiyage.backend.shipment

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.util.UUID

@Entity
@Table(name = "shipping_rates")
class ShippingRate(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var destination: ShipmentDestination,

    @Enumerated(EnumType.STRING)
    @Column(name = "shipping_method", nullable = false)
    var shippingMethod: ShippingMethod,

    // The rate applies to any parcel up to this weight - see ShippingRateService.priceFor, which
    // picks the cheapest tier whose maxWeightGrams still covers the parcel.
    @Column(name = "max_weight_grams", nullable = false)
    var maxWeightGrams: Int,

    @Column(name = "cost_jpy", nullable = false)
    var costJpy: Int,

    @Column(name = "estimated_days", nullable = false)
    var estimatedDays: Int,
)
