package com.omiyage.backend.order

import com.omiyage.backend.PostgresIntegrationTest
import com.omiyage.backend.catalog.InventoryItemRequest
import com.omiyage.backend.catalog.InventoryService
import com.omiyage.backend.common.Condition
import com.omiyage.backend.common.Currency
import com.omiyage.backend.security.MeishiPrincipal
import com.omiyage.backend.security.Role
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.domain.PageRequest
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.util.UUID

@SpringBootTest
@Transactional
class OrderServiceIntegrationTest : PostgresIntegrationTest() {

    @Autowired
    lateinit var orderService: OrderService

    @Autowired
    lateinit var inventoryService: InventoryService

    private fun user() = MeishiPrincipal(UUID.randomUUID(), "bob", Role.USER)
    private fun admin() = MeishiPrincipal(UUID.randomUUID(), "root", Role.ADMIN)

    @Test
    fun `creates an order with a custom item and JIT-provisions the user profile`() {
        val principal = user()
        val request = CreateOrderRequest(
            items = listOf(
                CreateOrderItemRequest(
                    title = "Ghibli museum ticket",
                    condition = Condition.NEW,
                    currency = Currency.JPY,
                    maxPrice = BigDecimal("2000"),
                ),
            ),
        )

        val order = orderService.create(principal, request)

        assertEquals(OrderStatus.REQUESTED, order.status)
        assertEquals(1, order.items.size)
        assertEquals("Ghibli museum ticket", order.items[0].title)
        assertNull(order.items[0].inventoryItemId)
    }

    @Test
    fun `creates an order item linked to an existing inventory item`() {
        val item = inventoryService.create(
            InventoryItemRequest(
                title = "Kit Kat matcha",
                description = null,
                condition = Condition.NEW,
                priceAmount = BigDecimal("500"),
                currency = Currency.JPY,
            ),
        )

        val order = orderService.create(
            user(),
            CreateOrderRequest(items = listOf(CreateOrderItemRequest(inventoryItemId = item.id, currency = Currency.JPY))),
        )

        assertEquals(item.id, order.items[0].inventoryItemId)
        assertEquals("Kit Kat matcha", order.items[0].title)
    }

    @Test
    fun `rejects a custom item with no title`() {
        val request = CreateOrderRequest(items = listOf(CreateOrderItemRequest(currency = Currency.JPY)))
        assertThrows(ResponseStatusException::class.java) { orderService.create(user(), request) }
    }

    @Test
    fun `rejects a custom item with no condition`() {
        val request = CreateOrderRequest(items = listOf(CreateOrderItemRequest(title = "Something", currency = Currency.JPY)))
        assertThrows(ResponseStatusException::class.java) { orderService.create(user(), request) }
    }

    @Test
    fun `a user cannot read another user's order`() {
        val owner = user()
        val order = orderService.create(
            owner,
            CreateOrderRequest(items = listOf(CreateOrderItemRequest(title = "X", condition = Condition.NEW, currency = Currency.JPY))),
        )

        assertThrows(ResponseStatusException::class.java) { orderService.getById(order.id, user()) }
        assertEquals(order.id, orderService.getById(order.id, owner).id)
    }

    @Test
    fun `an admin can read any order`() {
        val order = orderService.create(
            user(),
            CreateOrderRequest(items = listOf(CreateOrderItemRequest(title = "X", condition = Condition.NEW, currency = Currency.JPY))),
        )

        assertEquals(order.id, orderService.getById(order.id, admin()).id)
    }

    @Test
    fun `updateItem marks an item purchased independently and preserves order status`() {
        val order = orderService.create(
            user(),
            CreateOrderRequest(
                items = listOf(
                    CreateOrderItemRequest(title = "A", condition = Condition.NEW, currency = Currency.JPY),
                    CreateOrderItemRequest(title = "B", condition = Condition.NEW, currency = Currency.JPY),
                ),
            ),
        )
        orderService.updateStatus(order.id, OrderStatus.IN_PROGRESS)

        val updated = orderService.updateItem(order.id, order.items[0].id, true, BigDecimal("450"))

        val purchasedItem = updated.items.first { it.id == order.items[0].id }
        val untouchedItem = updated.items.first { it.id == order.items[1].id }
        assertTrue(purchasedItem.purchased)
        assertEquals(BigDecimal("450"), purchasedItem.actualPrice)
        assertTrue(!untouchedItem.purchased)
        assertEquals(OrderStatus.IN_PROGRESS, updated.status)
    }

    @Test
    fun `listMine only returns the caller's orders`() {
        val owner = user()
        orderService.create(owner, CreateOrderRequest(items = listOf(CreateOrderItemRequest(title = "A", condition = Condition.NEW, currency = Currency.JPY))))
        orderService.create(user(), CreateOrderRequest(items = listOf(CreateOrderItemRequest(title = "B", condition = Condition.NEW, currency = Currency.JPY))))

        val page = orderService.listMine(owner, PageRequest.of(0, 20))

        assertEquals(1, page.content.size)
        assertEquals(owner.id, page.content[0].userId)
    }
}
