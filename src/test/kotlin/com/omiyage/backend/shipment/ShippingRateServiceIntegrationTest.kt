package com.omiyage.backend.shipment

import com.omiyage.backend.PostgresIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException

@SpringBootTest
@Transactional
class ShippingRateServiceIntegrationTest : PostgresIntegrationTest() {

    @Autowired
    lateinit var service: ShippingRateService

    @Test
    fun `seeded EMS rate to Slovenia at 1kg matches the previous hardcoded table`() {
        val quote = service.priceFor(ShippingMethod.EMS, ShipmentDestination.SLOVENIA, 1000)
        assertEquals(4400, quote?.costJpy)
    }

    @Test
    fun `picks the cheapest tier that still covers the weight, not an exact match`() {
        // 501g has no exact tier - the previous hardcoded table jumped from 500g/3150 to 600g/3400.
        val quote = service.priceFor(ShippingMethod.EMS, ShipmentDestination.SLOVENIA, 501)
        assertEquals(3400, quote?.costJpy)
    }

    @Test
    fun `returns null above the heaviest configured tier`() {
        assertNull(service.priceFor(ShippingMethod.SMALL_PACKET, ShipmentDestination.AUSTRIA, 2001))
    }

    @Test
    fun `admin can add a new rate and it's immediately usable for pricing`() {
        service.create(
            ShippingRateRequest(
                destination = ShipmentDestination.AUSTRIA,
                shippingMethod = ShippingMethod.EMS,
                maxWeightGrams = 31000,
                costJpy = 67000,
                estimatedDays = 8,
            ),
        )

        val quote = service.priceFor(ShippingMethod.EMS, ShipmentDestination.AUSTRIA, 30500)
        assertEquals(67000, quote?.costJpy)
        assertEquals(8, quote?.estimatedDays)
    }

    @Test
    fun `admin can update and delete a rate`() {
        val created = service.create(
            ShippingRateRequest(
                destination = ShipmentDestination.SLOVENIA,
                shippingMethod = ShippingMethod.SURFACE_MAIL,
                maxWeightGrams = 3000,
                costJpy = 2600,
                estimatedDays = 65,
            ),
        )

        val updated = service.update(
            created.id,
            ShippingRateRequest(created.destination, created.shippingMethod, created.maxWeightGrams, 2700, created.estimatedDays),
        )
        assertEquals(2700, updated.costJpy)

        service.delete(created.id)
        assertTrue(service.listAll().none { it.id == created.id })
        assertThrows(ResponseStatusException::class.java) {
            service.update(created.id, ShippingRateRequest(updated.destination, updated.shippingMethod, updated.maxWeightGrams, updated.costJpy, updated.estimatedDays))
        }
    }
}
