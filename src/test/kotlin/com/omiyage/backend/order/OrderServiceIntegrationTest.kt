package com.omiyage.backend.order

import com.omiyage.backend.PostgresIntegrationTest
import com.omiyage.backend.catalog.InventoryItemRequest
import com.omiyage.backend.catalog.InventoryService
import com.omiyage.backend.common.Condition
import com.omiyage.backend.common.Currency
import com.omiyage.backend.security.MeishiPrincipal
import com.omiyage.backend.security.Role
import com.omiyage.backend.shipment.CreateShipmentRequest
import com.omiyage.backend.shipment.ShipmentRepository
import com.omiyage.backend.shipment.ShipmentService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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

    @Autowired
    lateinit var shipmentService: ShipmentService

    @Autowired
    lateinit var shipmentRepository: ShipmentRepository

    @Autowired
    lateinit var orderRepository: OrderRepository

    private fun user() = MeishiPrincipal(UUID.randomUUID(), "bob", Role.USER)
    private fun admin() = MeishiPrincipal(UUID.randomUUID(), "root", Role.ADMIN)

    private fun singleItemOrder(principal: MeishiPrincipal = user(), title: String = "A") = orderService.create(
        principal,
        CreateOrderRequest(items = listOf(CreateOrderItemRequest(title = title, condition = Condition.NEW, currency = Currency.JPY))),
    )

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
    fun `updateItem changes an item's status independently and preserves order status`() {
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

        val updated = orderService.updateItem(order.id, order.items[0].id, OrderItemStatus.BOUGHT, BigDecimal("450"))

        val boughtItem = updated.items.first { it.id == order.items[0].id }
        val untouchedItem = updated.items.first { it.id == order.items[1].id }
        assertEquals(OrderItemStatus.BOUGHT, boughtItem.status)
        assertEquals(BigDecimal("450"), boughtItem.actualPrice)
        assertEquals(OrderItemStatus.REQUESTED, untouchedItem.status)
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

    @Test
    fun `a user can add an item to their own open order`() {
        val owner = user()
        val order = singleItemOrder(owner)

        val updated = orderService.addOwnItem(
            order.id,
            owner,
            CreateOrderItemRequest(title = "B", condition = Condition.NEW, currency = Currency.JPY),
        )

        assertEquals(2, updated.items.size)
    }

    @Test
    fun `a user cannot add an item to a closed order`() {
        val owner = user()
        val order = singleItemOrder(owner)
        orderService.updateStatus(order.id, OrderStatus.SENT)

        assertThrows(ResponseStatusException::class.java) {
            orderService.addOwnItem(order.id, owner, CreateOrderItemRequest(title = "B", condition = Condition.NEW, currency = Currency.JPY))
        }
    }

    @Test
    fun `a user cannot edit or remove items on a closed order`() {
        val owner = user()
        val order = singleItemOrder(owner)
        orderService.updateStatus(order.id, OrderStatus.DELIVERED)
        val itemId = order.items[0].id

        assertThrows(ResponseStatusException::class.java) {
            orderService.updateOwnItem(order.id, itemId, owner, UpdateOwnOrderItemRequest(title = "Renamed"))
        }
        assertThrows(ResponseStatusException::class.java) {
            orderService.removeOwnItem(order.id, itemId, owner)
        }
    }

    @Test
    fun `an admin can add, edit and remove items on someone else's order regardless of open status`() {
        val order = singleItemOrder()
        orderService.updateStatus(order.id, OrderStatus.CANCELLED)

        val withNewItem = orderService.adminAddItem(order.id, CreateOrderItemRequest(title = "B", condition = Condition.NEW, currency = Currency.JPY))
        assertEquals(2, withNewItem.items.size)

        val edited = orderService.adminUpdateItemContent(order.id, order.items[0].id, UpdateOwnOrderItemRequest(title = "Renamed"))
        assertEquals("Renamed", edited.items.first { it.id == order.items[0].id }.title)

        val afterRemoval = orderService.adminRemoveItem(order.id, order.items[0].id)
        assertEquals(1, afterRemoval.items.size)
    }

    @Test
    fun `an admin can delete an order`() {
        val order = singleItemOrder()

        orderService.adminDelete(order.id)

        assertThrows(ResponseStatusException::class.java) { orderService.getById(order.id, admin()) }
    }

    @Test
    fun `merging orders deletes a source order's shipment once it has no orders left`() {
        val owner = user()
        val target = singleItemOrder(owner, "target")
        val source = singleItemOrder(owner, "source")
        val shipment = shipmentService.create(CreateShipmentRequest(orderIds = listOf(source.id), notes = null))

        orderService.mergeMine(owner, listOf(target.id, source.id))

        assertFalse(shipmentRepository.existsById(shipment.id))
    }

    @Test
    fun `combining orders into a shipment deletes an order's now-empty previous shipment`() {
        val owner = user()
        val solo = singleItemOrder(owner, "solo")
        val a = singleItemOrder(owner, "a")
        val b = singleItemOrder(owner, "b")

        // solo gets its own shipment first, then gets combined into a new shipment together with
        // a and b - that first shipment should end up with zero orders and be deleted.
        val soloShipment = shipmentService.create(CreateShipmentRequest(orderIds = listOf(solo.id), notes = null))
        shipmentService.create(CreateShipmentRequest(orderIds = listOf(solo.id, a.id, b.id), notes = null))

        assertFalse(orderRepository.existsByShipment_Id(soloShipment.id) || shipmentRepository.existsById(soloShipment.id))
    }

    @Test
    fun `reassigning an order off a shipment deletes it once empty, keeps it while other orders remain`() {
        val owner = user()
        val a = singleItemOrder(owner, "a")
        val b = singleItemOrder(owner, "b")
        val shipment = shipmentService.create(CreateShipmentRequest(orderIds = listOf(a.id, b.id), notes = null))

        shipmentService.assignOrder(a.id, null)
        assertTrue(shipmentRepository.existsById(shipment.id))

        shipmentService.assignOrder(b.id, null)
        assertFalse(shipmentRepository.existsById(shipment.id))
    }
}
