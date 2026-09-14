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

    // Mirrors ExchangeRateService.toEurWithMarkup's formula (rate 150 JPY/EUR, +15% markup) without
    // needing the DB-backed service - these tests only care about the cost-sharing math above it.
    private fun stubToEur(jpyAmount: Int): BigDecimal =
        BigDecimal(jpyAmount).divide(BigDecimal(150), 6, java.math.RoundingMode.HALF_UP)
            .multiply(BigDecimal("1.15")).setScale(2, java.math.RoundingMode.HALF_UP)

    // Mirrors ExchangeRateService.toEur (bare, no markup) - none of these tests set an item's
    // actualPrice, so this is never actually invoked, just needed to satisfy toDto()'s signature.
    private fun stubToEurBare(jpyAmount: BigDecimal): BigDecimal =
        jpyAmount.divide(BigDecimal(150), 2, java.math.RoundingMode.HALF_UP)

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

        val dto = shipment.toDto(::stubRates, ::stubToEur, ::stubToEurBare)

        assertEquals(BigDecimal(500), dto.totalWeightGrams)
        assertNull(dto.shippingCostJpy)
        assertNull(dto.orders[0].shippingCostShareJpy)
    }

    @Test
    fun `splits shipping cost proportionally by each order's weight and reconciles rounding`() {
        val shipment = Shipment(id = UUID.randomUUID(), destination = ShipmentDestination.AUSTRIA, shippingMethod = ShippingMethod.EMS)
        shipment.orders.add(orderWeighing(300))
        shipment.orders.add(orderWeighing(700))

        val dto = shipment.toDto(::stubRates, ::stubToEur, ::stubToEurBare)

        assertEquals(BigDecimal(1000), dto.totalWeightGrams)
        assertEquals(4400, dto.shippingCostJpy) // EMS, Third Zone, up to 1kg

        val shares = dto.orders.map { it.shippingCostShareJpy }
        assertEquals(listOf(1320, 3080), shares) // 30% / 70% of 4400, exact split (no rounding drift here)
        assertEquals(4400, shares.sumOf { it ?: 0 })

        assertEquals(stubToEur(4400), dto.shippingCostEur)
        val eurShares = dto.orders.map { it.shippingCostShareEur }
        assertEquals(listOf(stubToEur(1320), stubToEur(3080)), eurShares)
    }

    @Test
    fun `a cancelled order is excluded from weight and cost share`() {
        val shipment = Shipment(id = UUID.randomUUID(), destination = ShipmentDestination.AUSTRIA, shippingMethod = ShippingMethod.EMS)
        val cancelled = orderWeighing(700).also { it.status = com.omiyage.backend.order.OrderStatus.CANCELLED }
        shipment.orders.add(orderWeighing(300))
        shipment.orders.add(cancelled)

        val dto = shipment.toDto(::stubRates, ::stubToEur, ::stubToEurBare)

        assertEquals(BigDecimal(300), dto.totalWeightGrams)
        assertEquals(4400, dto.shippingCostJpy) // priced for 300g, not 1000g - EMS up to 1kg tier still applies
        val cancelledDto = dto.orders.first { it.id == cancelled.id }
        assertNull(cancelledDto.orderWeightGrams)
        assertNull(cancelledDto.shippingCostShareJpy)
        val activeDto = dto.orders.first { it.id != cancelled.id }
        assertEquals(4400, activeDto.shippingCostShareJpy) // entire cost falls on the one billable order
    }

    @Test
    fun `a shipment heavier than the chosen method's limit reports no cost`() {
        val shipment = Shipment(id = UUID.randomUUID(), destination = ShipmentDestination.SLOVENIA, shippingMethod = ShippingMethod.SMALL_PACKET)
        shipment.orders.add(orderWeighing(2500)) // over Small Packet's 2kg cap

        val dto = shipment.toDto(::stubRates, ::stubToEur, ::stubToEurBare)

        assertEquals(BigDecimal(2500), dto.totalWeightGrams)
        assertNull(dto.shippingCostJpy)
        assertNull(dto.orders[0].shippingCostShareJpy)
    }

    @Test
    fun `toMemberDto strips admin-only pricing but keeps it in the item total it already computed`() {
        val shipment = Shipment(id = UUID.randomUUID(), destination = ShipmentDestination.AUSTRIA, shippingMethod = ShippingMethod.EMS)
        val order = orderWeighing(300)
        order.items[0].actualPrice = BigDecimal("100")
        order.items[0].actualPriceCurrency = Currency.JPY
        order.items[0].soldPrice = BigDecimal("60")
        order.items[0].maxPrice = BigDecimal("50")
        order.items[0].status = com.omiyage.backend.order.OrderItemStatus.BOUGHT
        shipment.orders.add(order)

        val dto = shipment.toDto(::stubRates, ::stubToEur, ::stubToEurBare).toMemberDto()

        val member = dto.members.single()
        val strippedItem = member.orders.single().items.single()
        assertNull(strippedItem.actualPrice)
        assertNull(strippedItem.actualPriceCurrency)
        assertNull(strippedItem.soldPrice)
        assertNull(strippedItem.profit)
        // 60 EUR soldPrice - what the member is actually charged - not the 100 JPY actualPrice
        // (the admin's own cost basis) and not the 50 EUR maxPrice fallback, proving the total was
        // computed from soldPrice before it got stripped out of the response above.
        assertEquals(BigDecimal("60.00"), member.itemTotals.single().amount)
    }
}
