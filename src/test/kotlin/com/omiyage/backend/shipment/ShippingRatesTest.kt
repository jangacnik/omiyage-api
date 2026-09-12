package com.omiyage.backend.shipment

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ShippingRatesTest {

    @Test
    fun `EMS to Slovenia picks the correct weight bracket`() {
        assertEquals(3150, ShippingRates.priceJpy(ShippingMethod.EMS, ShipmentDestination.SLOVENIA, 500))
        assertEquals(3400, ShippingRates.priceJpy(ShippingMethod.EMS, ShipmentDestination.SLOVENIA, 501))
        assertEquals(4400, ShippingRates.priceJpy(ShippingMethod.EMS, ShipmentDestination.SLOVENIA, 1000))
        assertEquals(65500, ShippingRates.priceJpy(ShippingMethod.EMS, ShipmentDestination.SLOVENIA, 30000))
    }

    @Test
    fun `Austria and Slovenia share the same Third Zone rates`() {
        val weights = listOf(100, 999, 2000, 15000)
        for (method in ShippingMethod.entries) {
            for (grams in weights) {
                assertEquals(
                    ShippingRates.priceJpy(method, ShipmentDestination.AUSTRIA, grams),
                    ShippingRates.priceJpy(method, ShipmentDestination.SLOVENIA, grams),
                )
            }
        }
    }

    @Test
    fun `Small Packet and Surface Mail return null above their 2kg cap`() {
        assertNull(ShippingRates.priceJpy(ShippingMethod.SMALL_PACKET, ShipmentDestination.AUSTRIA, 2001))
        assertNull(ShippingRates.priceJpy(ShippingMethod.SURFACE_MAIL, ShipmentDestination.AUSTRIA, 2001))
        assertEquals(3930, ShippingRates.priceJpy(ShippingMethod.SMALL_PACKET, ShipmentDestination.AUSTRIA, 2000))
        assertEquals(2200, ShippingRates.priceJpy(ShippingMethod.SURFACE_MAIL, ShipmentDestination.AUSTRIA, 2000))
    }

    @Test
    fun `EMS returns null above its 30kg cap`() {
        assertNull(ShippingRates.priceJpy(ShippingMethod.EMS, ShipmentDestination.AUSTRIA, 30001))
    }

    @Test
    fun `Surface Mail is always cheaper than EMS at the same weight`() {
        for (grams in listOf(100, 500, 1000, 2000)) {
            val surface = requireNotNull(ShippingRates.priceJpy(ShippingMethod.SURFACE_MAIL, ShipmentDestination.AUSTRIA, grams))
            val ems = requireNotNull(ShippingRates.priceJpy(ShippingMethod.EMS, ShipmentDestination.AUSTRIA, grams))
            assert(surface < ems) { "expected surface mail ($surface) < EMS ($ems) at ${grams}g" }
        }
    }
}
