package com.omiyage.backend.shipment

import com.omiyage.backend.common.Currency
import com.omiyage.backend.order.Order
import com.omiyage.backend.order.OrderItem
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import java.math.BigDecimal
import java.util.UUID

class ShipmentDtosTest {

    // toDto() no longer looks up rates itself (that's ShippingRateService, backed by the DB) - a
    // small in-memory stub is enough to exercise the cost-sharing/rounding math these tests cover.
    private fun stubRates(method: ShippingMethod, destination: ShipmentDestination, grams: Int): ShippingQuote? =
        when (method) {
            ShippingMethod.EMS -> if (grams <= 1000) ShippingQuote(4400, 7) else null
            ShippingMethod.SMALL_PACKET -> if (grams <= 2000) ShippingQuote(3930, 12) else null
            ShippingMethod.SURFACE_MAIL -> null
        }

    private fun orderWeighing(grams: Int): Order {
        val order = Order(id = UUID.randomUUID(), userId = UUID.randomUUID())
        order.items.add(
            OrderItem(
                id = UUID.randomUUID(),
                order = order,
                customTitle = "item",
                currency = Currency.JPY,
                quantity = 1,
                weight = BigDecimal(grams),
            ),
        )
        return order
    }

    @Test
    fun `no destination or method means no cost, but weight is still reported`() {
        val shipment = Shipment(id = UUID.randomUUID())
        shipment.orders.add(orderWeighing(500))

        val dto = shipment.toDto(::stubRates)

        assertEquals(BigDecimal(500), dto.totalWeightGrams)
        assertNull(dto.shippingCostJpy)
        assertNull(dto.orders[0].shippingCostShareJpy)
    }

    @Test
    fun `splits shipping cost proportionally by each order's weight and reconciles rounding`() {
        val shipment = Shipment(id = UUID.randomUUID(), destination = ShipmentDestination.AUSTRIA, shippingMethod = ShippingMethod.EMS)
        shipment.orders.add(orderWeighing(300))
        shipment.orders.add(orderWeighing(700))

        val dto = shipment.toDto(::stubRates)

        assertEquals(BigDecimal(1000), dto.totalWeightGrams)
        assertEquals(4400, dto.shippingCostJpy) // EMS, Third Zone, up to 1kg

        val shares = dto.orders.map { it.shippingCostShareJpy }
        assertEquals(listOf(1320, 3080), shares) // 30% / 70% of 4400, exact split (no rounding drift here)
        assertEquals(4400, shares.sumOf { it ?: 0 })
    }

    @Test
    fun `a shipment heavier than the chosen method's limit reports no cost`() {
        val shipment = Shipment(id = UUID.randomUUID(), destination = ShipmentDestination.SLOVENIA, shippingMethod = ShippingMethod.SMALL_PACKET)
        shipment.orders.add(orderWeighing(2500)) // over Small Packet's 2kg cap

        val dto = shipment.toDto(::stubRates)

        assertEquals(BigDecimal(2500), dto.totalWeightGrams)
        assertNull(dto.shippingCostJpy)
        assertNull(dto.orders[0].shippingCostShareJpy)
    }
}
