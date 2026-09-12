package com.omiyage.backend.shipment

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ShippingRateRepository : JpaRepository<ShippingRate, UUID> {
    fun findByDestinationAndShippingMethodOrderByMaxWeightGramsAsc(
        destination: ShipmentDestination,
        shippingMethod: ShippingMethod,
    ): List<ShippingRate>

    fun findAllByOrderByDestinationAscShippingMethodAscMaxWeightGramsAsc(): List<ShippingRate>
}
