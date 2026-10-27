package com.omiyage.backend.order

import com.omiyage.backend.common.Currency
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

class OrderDtosTest {

    private fun itemWithPricing(): OrderItem {
        val order = Order(id = UUID.randomUUID(), userId = UUID.randomUUID())
        val item = OrderItem(
            id = UUID.randomUUID(),
            order = order,
            customTitle = "item",
            currency = Currency.EUR,
            quantity = 1,
            maxPrice = BigDecimal("50.00"),
            actualPrice = BigDecimal("6000"),
            actualPriceCurrency = Currency.JPY,
            soldPrice = BigDecimal("55.00"),
            soldPriceCurrency = Currency.EUR,
            purchasedAt = OffsetDateTime.now(),
        )
        order.items.add(item)
        return item
    }

    @Test
    fun `actualPrice is admin-only - hidden without includeProfit`() {
        val dto = itemWithPricing().toDto()

        assertNull(dto.actualPrice)
        assertNull(dto.actualPriceCurrency)
        assertNull(dto.purchasedAt)
        // maxPrice and soldPrice are what a normal user sees, and stay populated regardless.
        assertEquals(BigDecimal("50.00"), dto.maxPrice)
        assertEquals(BigDecimal("55.00"), dto.soldPrice)
    }

    @Test
    fun `actualPrice and purchasedAt are included for an admin, via includeProfit`() {
        val item = itemWithPricing()
        val dto = item.toDto(includeProfit = true)

        assertEquals(BigDecimal("6000"), dto.actualPrice)
        assertEquals(Currency.JPY, dto.actualPriceCurrency)
        assertEquals(item.purchasedAt, dto.purchasedAt)
    }

    @Test
    fun `iconUrl points at the first item's image`() {
        val item = itemWithPricing().apply { customImageKey = "uploads/a.png" }

        assertEquals("/api/images/uploads/a.png", item.order.toDto().iconUrl)
    }

    @Test
    fun `iconUrl is null when the first item has no image`() {
        assertNull(itemWithPricing().order.toDto().iconUrl)
    }
}
