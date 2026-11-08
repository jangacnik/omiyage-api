package com.omiyage.backend.errors

import com.omiyage.backend.basket.AddBasketLineRequest
import com.omiyage.backend.basket.AddBasketToOrderRequest
import com.omiyage.backend.basket.UpdateBasketLineRequest
import com.omiyage.backend.common.Currency
import com.omiyage.backend.order.OrderStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import java.util.UUID

class BasketErrorContractTest : ErrorContractTest() {

    private val user = newCaller()
    private val other = newCaller()
    private val admin = newCaller("ADMIN")

    private fun addCustomLine(): UUID {
        val result = send(user, HttpMethod.POST, "/api/basket/lines", AddBasketLineRequest(customTitle = uniqueTitle(), currency = Currency.EUR))
        assertEquals(201, result.response.status)
        return uuidOf(json(result))
    }

    @Test
    fun `a line with neither inventory item nor title is 400`() {
        val result = send(user, HttpMethod.POST, "/api/basket/lines", AddBasketLineRequest(currency = Currency.EUR))
        assertBadRequest(result, "a basket line needs an inventory item or a custom title")
    }

    @Test
    fun `a line for an unknown inventory item is 404`() {
        val result = send(user, HttpMethod.POST, "/api/basket/lines", AddBasketLineRequest(inventoryItemId = UUID.randomUUID(), currency = Currency.EUR))
        assertNotFound(result, "inventory item not found")
    }

    @Test
    fun `updating an unknown line is 404`() {
        assertNotFound(send(user, HttpMethod.PUT, "/api/basket/lines/${UUID.randomUUID()}", UpdateBasketLineRequest()), "basket line not found")
    }

    @Test
    fun `removing an unknown line is 404`() {
        assertNotFound(send(user, HttpMethod.DELETE, "/api/basket/lines/${UUID.randomUUID()}"), "basket line not found")
    }

    @Test
    fun `updating someone else's line is 404`() {
        val lineId = addCustomLine()
        assertNotFound(send(other, HttpMethod.PUT, "/api/basket/lines/$lineId", UpdateBasketLineRequest(quantity = 2)), "basket line not found")
    }

    @Test
    fun `blanking the title of a custom line is 400`() {
        val lineId = addCustomLine()
        assertBadRequest(send(user, HttpMethod.PUT, "/api/basket/lines/$lineId", UpdateBasketLineRequest(customTitle = " ")), "a custom line needs a title")
    }

    @Test
    fun `confirming an empty basket is 409`() {
        assertConflict(send(user, HttpMethod.POST, "/api/basket/confirm"), "basket is empty")
    }

    @Test
    fun `adding an empty basket to an order is 409`() {
        assertConflict(send(user, HttpMethod.POST, "/api/basket/add-to-order", AddBasketToOrderRequest(UUID.randomUUID())), "basket is empty")
    }

    @Test
    fun `adding the basket to an unknown order is 404`() {
        addCustomLine()
        assertNotFound(send(user, HttpMethod.POST, "/api/basket/add-to-order", AddBasketToOrderRequest(UUID.randomUUID())), "order not found")
    }

    @Test
    fun `adding the basket to someone else's order is 404`() {
        addCustomLine()
        val orderId = uuidOf(createOrder(other))
        assertNotFound(send(user, HttpMethod.POST, "/api/basket/add-to-order", AddBasketToOrderRequest(orderId)), "order not found")
    }

    @Test
    fun `adding the basket to a closed order is 409`() {
        addCustomLine()
        val orderId = uuidOf(createOrder(user))
        setOrderStatus(admin, orderId, OrderStatus.PREPARING_SHIPPING)
        assertConflict(send(user, HttpMethod.POST, "/api/basket/add-to-order", AddBasketToOrderRequest(orderId)), "order is closed and can no longer be edited")
    }

    @Test
    fun `confirming a basket whose inventory item was deleted is 404`() {
        val inventoryItemId = createInventoryItem(admin)
        val added = send(user, HttpMethod.POST, "/api/basket/lines", AddBasketLineRequest(inventoryItemId = inventoryItemId, currency = Currency.EUR))
        assertEquals(201, added.response.status)
        assertEquals(204, send(admin, HttpMethod.DELETE, "/api/admin/inventory/$inventoryItemId").response.status)
        assertNotFound(send(user, HttpMethod.POST, "/api/basket/confirm"), "inventory item not found")
    }
}
