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
import com.omiyage.backend.shipment.ShipmentStatus
import com.omiyage.backend.shipment.UpdateShipmentRequest
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
    fun `an admin can create an order on behalf of a specific user`() {
        val targetUserId = UUID.randomUUID()
        val request = CreateOrderRequest(
            items = listOf(CreateOrderItemRequest(title = "A", condition = Condition.NEW, currency = Currency.JPY)),
        )

        val order = orderService.adminCreate(targetUserId, request)

        assertEquals(targetUserId, order.userId)
        assertEquals(1, orderService.listMine(MeishiPrincipal(targetUserId, "whoever", Role.USER), org.springframework.data.domain.Pageable.unpaged()).content.size)
    }

    @Test
    fun `sets the nickname given at creation, trimmed, blank means none`() {
        val request = CreateOrderRequest(
            nickname = "  Grandma's gifts  ",
            items = listOf(CreateOrderItemRequest(title = "A", condition = Condition.NEW, currency = Currency.JPY)),
        )

        val order = orderService.create(user(), request)

        assertEquals("Grandma's gifts", order.nickname)

        val blank = orderService.create(
            user(),
            request.copy(nickname = "   "),
        )
        assertNull(blank.nickname)
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
    fun `ordering an inventory-linked item decrements its stock`() {
        val item = inventoryService.create(
            InventoryItemRequest(
                title = "Kit Kat matcha",
                description = null,
                condition = Condition.NEW,
                priceAmount = BigDecimal("500"),
                currency = Currency.JPY,
                stockQuantity = 3,
            ),
        )

        orderService.create(
            user(),
            CreateOrderRequest(items = listOf(CreateOrderItemRequest(inventoryItemId = item.id, currency = Currency.JPY, quantity = 2))),
        )

        assertEquals(1, inventoryService.getById(item.id).stockQuantity)
    }

    @Test
    fun `ordering more than the available stock floors it at zero instead of going negative or failing`() {
        val item = inventoryService.create(
            InventoryItemRequest(
                title = "Kit Kat matcha",
                description = null,
                condition = Condition.NEW,
                priceAmount = BigDecimal("500"),
                currency = Currency.JPY,
                stockQuantity = 1,
            ),
        )

        val order = orderService.create(
            user(),
            CreateOrderRequest(items = listOf(CreateOrderItemRequest(inventoryItemId = item.id, currency = Currency.JPY, quantity = 5))),
        )

        assertEquals(1, order.items.size)
        assertEquals(0, inventoryService.getById(item.id).stockQuantity)
    }

    @Test
    fun `removing an inventory-linked item restores its stock`() {
        val item = inventoryService.create(
            InventoryItemRequest(
                title = "Kit Kat matcha",
                description = null,
                condition = Condition.NEW,
                priceAmount = BigDecimal("500"),
                currency = Currency.JPY,
                stockQuantity = 5,
            ),
        )
        val owner = user()
        val order = orderService.create(
            owner,
            CreateOrderRequest(items = listOf(CreateOrderItemRequest(inventoryItemId = item.id, currency = Currency.JPY, quantity = 2))),
        )
        assertEquals(3, inventoryService.getById(item.id).stockQuantity)

        orderService.removeOwnItem(order.id, order.items[0].id, owner)

        assertEquals(5, inventoryService.getById(item.id).stockQuantity)
    }

    @Test
    fun `cancelling an order restores stock for its inventory-linked items`() {
        val item = inventoryService.create(
            InventoryItemRequest(
                title = "Kit Kat matcha",
                description = null,
                condition = Condition.NEW,
                priceAmount = BigDecimal("500"),
                currency = Currency.JPY,
                stockQuantity = 5,
            ),
        )
        val owner = user()
        val order = orderService.create(
            owner,
            CreateOrderRequest(items = listOf(CreateOrderItemRequest(inventoryItemId = item.id, currency = Currency.JPY, quantity = 2))),
        )
        assertEquals(3, inventoryService.getById(item.id).stockQuantity)

        orderService.cancelOwn(order.id, owner)

        assertEquals(5, inventoryService.getById(item.id).stockQuantity)
    }

    @Test
    fun `editing an inventory-linked item's quantity adjusts stock by the difference`() {
        val item = inventoryService.create(
            InventoryItemRequest(
                title = "Kit Kat matcha",
                description = null,
                condition = Condition.NEW,
                priceAmount = BigDecimal("500"),
                currency = Currency.JPY,
                stockQuantity = 5,
            ),
        )
        val owner = user()
        val order = orderService.create(
            owner,
            CreateOrderRequest(items = listOf(CreateOrderItemRequest(inventoryItemId = item.id, currency = Currency.JPY, quantity = 2))),
        )
        assertEquals(3, inventoryService.getById(item.id).stockQuantity)

        orderService.updateOwnItem(order.id, order.items[0].id, owner, UpdateOwnOrderItemRequest(quantity = 4))
        assertEquals(1, inventoryService.getById(item.id).stockQuantity)

        orderService.updateOwnItem(order.id, order.items[0].id, owner, UpdateOwnOrderItemRequest(quantity = 1))
        assertEquals(4, inventoryService.getById(item.id).stockQuantity)
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
    fun `updateItem computes profit as soldPrice minus actualPrice and sums it to order level`() {
        val order = orderService.create(
            user(),
            CreateOrderRequest(
                items = listOf(
                    CreateOrderItemRequest(title = "A", condition = Condition.NEW, currency = Currency.JPY),
                    CreateOrderItemRequest(title = "B", condition = Condition.NEW, currency = Currency.JPY),
                ),
            ),
        )

        orderService.updateItem(order.id, order.items[0].id, OrderItemStatus.BOUGHT, BigDecimal("450"), BigDecimal("700"))
        val updated = orderService.updateItem(order.id, order.items[1].id, OrderItemStatus.BOUGHT, BigDecimal("300"), BigDecimal("300"))

        val firstItem = updated.items.first { it.id == order.items[0].id }
        val secondItem = updated.items.first { it.id == order.items[1].id }
        assertEquals(BigDecimal("250"), firstItem.profit)
        assertEquals(BigDecimal.ZERO, secondItem.profit)
        assertEquals(BigDecimal("250"), updated.profit)
    }

    @Test
    fun `an item with no soldPrice yet has no profit`() {
        val order = singleItemOrder()
        orderService.updateStatus(order.id, OrderStatus.IN_PROGRESS)

        val updated = orderService.updateItem(order.id, order.items[0].id, OrderItemStatus.BOUGHT, BigDecimal("450"))

        assertNull(updated.items[0].profit)
        assertEquals(BigDecimal.ZERO, updated.profit)
    }

    @Test
    fun `getById never exposes soldPrice or profit to the owning user`() {
        val owner = user()
        val order = singleItemOrder(owner)
        orderService.updateItem(order.id, order.items[0].id, OrderItemStatus.BOUGHT, BigDecimal("450"), BigDecimal("700"))

        val fetched = orderService.getById(order.id, owner)

        assertNull(fetched.items[0].soldPrice)
        assertNull(fetched.items[0].profit)
        assertNull(fetched.profit)
    }

    @Test
    fun `getById exposes soldPrice and profit to an admin`() {
        val owner = user()
        val order = singleItemOrder(owner)
        orderService.updateItem(order.id, order.items[0].id, OrderItemStatus.BOUGHT, BigDecimal("450"), BigDecimal("700"))

        val fetched = orderService.getById(order.id, admin())

        assertEquals(BigDecimal("700"), fetched.items[0].soldPrice)
        assertEquals(BigDecimal("250"), fetched.items[0].profit)
        assertEquals(BigDecimal("250"), fetched.profit)
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
    fun `deleting the last order on a shipment deletes the shipment too`() {
        val owner = user()
        val solo = singleItemOrder(owner, "solo")
        val shipment = shipmentService.create(CreateShipmentRequest(orderIds = listOf(solo.id), notes = null))

        orderService.adminDelete(solo.id)

        assertFalse(shipmentRepository.existsById(shipment.id))
    }

    @Test
    fun `deleting one of several orders on a shipment keeps the shipment`() {
        val owner = user()
        val a = singleItemOrder(owner, "a")
        val b = singleItemOrder(owner, "b")
        val shipment = shipmentService.create(CreateShipmentRequest(orderIds = listOf(a.id, b.id), notes = null))

        orderService.adminDelete(a.id)

        assertTrue(shipmentRepository.existsById(shipment.id))
    }

    @Test
    fun `cannot merge a closed source order into an open target`() {
        val owner = user()
        val target = singleItemOrder(owner, "target")
        val source = singleItemOrder(owner, "source")
        orderService.updateStatus(source.id, OrderStatus.CANCELLED)

        assertThrows(ResponseStatusException::class.java) { orderService.mergeMine(owner, listOf(target.id, source.id)) }
    }

    @Test
    fun `cannot merge an open source into a closed target`() {
        val owner = user()
        val target = singleItemOrder(owner, "target")
        val source = singleItemOrder(owner, "source")
        orderService.updateStatus(target.id, OrderStatus.DELIVERED)

        assertThrows(ResponseStatusException::class.java) { orderService.mergeMine(owner, listOf(target.id, source.id)) }
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
    fun `combining a solo order (listed first) with others absorbs them into its existing shipment`() {
        val owner = user()
        val solo = singleItemOrder(owner, "solo")
        val a = singleItemOrder(owner, "a")
        val b = singleItemOrder(owner, "b")
        val soloShipment = shipmentService.create(CreateShipmentRequest(orderIds = listOf(solo.id), notes = null))

        val combined = shipmentService.create(CreateShipmentRequest(orderIds = listOf(solo.id, a.id, b.id), notes = null))

        assertEquals(soloShipment.id, combined.id)
        assertTrue(shipmentRepository.existsById(soloShipment.id))
        assertEquals(3, combined.orders.size)
        assertEquals(owner.id, combined.ownerUserId)
    }

    @Test
    fun `combining two orders each already on their own shipment keeps only the absorbing (first-listed) one`() {
        val owner = user()
        val a = singleItemOrder(owner, "a")
        val b = singleItemOrder(owner, "b")
        val shipmentA = shipmentService.create(CreateShipmentRequest(orderIds = listOf(a.id), notes = null))
        val shipmentB = shipmentService.create(CreateShipmentRequest(orderIds = listOf(b.id), notes = null))

        val combined = shipmentService.create(CreateShipmentRequest(orderIds = listOf(a.id, b.id), notes = null))

        assertEquals(shipmentA.id, combined.id)
        assertTrue(shipmentRepository.existsById(shipmentA.id))
        assertFalse(shipmentRepository.existsById(shipmentB.id))
    }

    @Test
    fun `an order that is no longer open cannot be combined into a shipment`() {
        val owner = user()
        val a = singleItemOrder(owner, "a")
        val b = singleItemOrder(owner, "b")
        orderService.updateStatus(b.id, OrderStatus.SENT)

        assertThrows(ResponseStatusException::class.java) {
            shipmentService.create(CreateShipmentRequest(orderIds = listOf(a.id, b.id), notes = null))
        }
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

    @Test
    fun `a user can cancel their own open order`() {
        val owner = user()
        val order = singleItemOrder(owner)

        val cancelled = orderService.cancelOwn(order.id, owner)

        assertEquals(OrderStatus.CANCELLED, cancelled.status)
        assertFalse(cancelled.open)
    }

    @Test
    fun `a user cannot cancel someone else's order or an already-closed order`() {
        val owner = user()
        val order = singleItemOrder(owner)

        assertThrows(ResponseStatusException::class.java) { orderService.cancelOwn(order.id, user()) }

        orderService.updateStatus(order.id, OrderStatus.DELIVERED)
        assertThrows(ResponseStatusException::class.java) { orderService.cancelOwn(order.id, owner) }
    }

    @Test
    fun `a user cannot cancel an order with a bought or shipped item, even while the order itself is still open`() {
        val owner = user()
        val order = singleItemOrder(owner)
        orderService.updateItem(order.id, order.items[0].id, OrderItemStatus.BOUGHT, BigDecimal("450"))
        orderService.updateStatus(order.id, OrderStatus.PURCHASED)

        assertThrows(ResponseStatusException::class.java) { orderService.cancelOwn(order.id, owner) }
    }

    @Test
    fun `a user cannot edit or remove a shipped item, but an admin can`() {
        val owner = user()
        val order = singleItemOrder(owner)
        val itemId = order.items[0].id
        orderService.updateItem(order.id, itemId, OrderItemStatus.SHIPPED, null)

        assertThrows(ResponseStatusException::class.java) {
            orderService.updateOwnItem(order.id, itemId, owner, UpdateOwnOrderItemRequest(title = "Renamed"))
        }
        assertThrows(ResponseStatusException::class.java) {
            orderService.removeOwnItem(order.id, itemId, owner)
        }

        val edited = orderService.adminUpdateItemContent(order.id, itemId, UpdateOwnOrderItemRequest(title = "Renamed"))
        assertEquals("Renamed", edited.items.first { it.id == itemId }.title)
        val afterRemoval = orderService.adminRemoveItem(order.id, itemId)
        assertTrue(afterRemoval.items.isEmpty())
    }

    @Test
    fun `an admin deleting a shipment detaches its orders instead of leaving a dangling reference`() {
        val owner = user()
        val a = singleItemOrder(owner, "a")
        val b = singleItemOrder(owner, "b")
        val shipment = shipmentService.create(CreateShipmentRequest(orderIds = listOf(a.id, b.id), notes = null))

        shipmentService.adminDelete(shipment.id)

        assertFalse(shipmentRepository.existsById(shipment.id))
        assertNull(orderService.getById(a.id, owner).shipmentId)
        assertNull(orderService.getById(b.id, owner).shipmentId)
        // The order itself must still load cleanly - this is exactly the dangling-FK class of bug
        // fixed earlier for soft-deleted inventory items.
        assertEquals(a.id, orderService.getById(a.id, owner).id)
    }

    @Test
    fun `a solo order's own owner can submit it for shipping`() {
        val owner = user()
        val order = singleItemOrder(owner)

        val submitted = orderService.submitForShipping(order.id, owner)

        assertEquals(OrderStatus.PREPARING_SHIPPING, submitted.status)
        assertFalse(submitted.open)
    }

    @Test
    fun `only the shipment's owner (the absorbing order's user) can submit a combined shipment`() {
        val owner = user()
        val other = user()
        val mine = singleItemOrder(owner, "mine")
        val theirs = singleItemOrder(other, "theirs")
        shipmentService.create(CreateShipmentRequest(orderIds = listOf(mine.id, theirs.id), notes = null))

        assertThrows(ResponseStatusException::class.java) { orderService.submitForShipping(theirs.id, other) }

        val submitted = orderService.submitForShipping(theirs.id, owner)
        assertEquals(OrderStatus.PREPARING_SHIPPING, submitted.status)
        // Submitting moves the whole shipment, not just the order the owner happened to name.
        assertEquals(OrderStatus.PREPARING_SHIPPING, orderService.getById(mine.id, owner).status)
    }

    @Test
    fun `submitting for shipping requires the order still be open`() {
        val owner = user()
        val order = singleItemOrder(owner)
        orderService.updateStatus(order.id, OrderStatus.CANCELLED)

        assertThrows(ResponseStatusException::class.java) { orderService.submitForShipping(order.id, owner) }
    }

    @Test
    fun `a shipment with no owner assigned authorizes nobody to submit it`() {
        // Simulates a shipment that predates owner_user_id, or one only ever touched via
        // assignOrder (which never sets it) - must not fall back to "whoever owns the order
        // named in the URL", or any co-member could submit by naming their own order.
        val owner = user()
        val other = user()
        val mine = singleItemOrder(owner, "mine")
        val theirs = singleItemOrder(other, "theirs")
        val orphanOwnerShipment = com.omiyage.backend.shipment.Shipment()
        val saved = shipmentRepository.save(orphanOwnerShipment)
        shipmentService.assignOrder(mine.id, saved.id)
        shipmentService.assignOrder(theirs.id, saved.id)

        assertThrows(ResponseStatusException::class.java) { orderService.submitForShipping(mine.id, owner) }
        assertThrows(ResponseStatusException::class.java) { orderService.submitForShipping(theirs.id, other) }
    }

    @Test
    fun `admin can mark an item paid and unpaid, tracking paidAt`() {
        val order = singleItemOrder()
        val itemId = order.items[0].id

        val paid = orderService.adminMarkItemPaid(order.id, itemId, true)
        val paidItem = paid.items.first { it.id == itemId }
        assertTrue(paidItem.paid)
        assertEquals(paidItem.paidAt != null, true)

        val unpaid = orderService.adminMarkItemPaid(order.id, itemId, false)
        val unpaidItem = unpaid.items.first { it.id == itemId }
        assertFalse(unpaidItem.paid)
        assertNull(unpaidItem.paidAt)
    }

    @Test
    fun `an item's optional link round-trips through create and edit`() {
        val owner = user()
        val order = orderService.create(
            owner,
            CreateOrderRequest(
                items = listOf(
                    CreateOrderItemRequest(
                        title = "A",
                        condition = Condition.NEW,
                        currency = Currency.JPY,
                        link = "https://example.com/product",
                    ),
                ),
            ),
        )
        assertEquals("https://example.com/product", order.items[0].link)

        val edited = orderService.updateOwnItem(
            order.id,
            order.items[0].id,
            owner,
            UpdateOwnOrderItemRequest(link = "https://example.com/updated"),
        )
        assertEquals("https://example.com/updated", edited.items[0].link)
    }

    @Test
    fun `cannot attach an order to a shipment that has already shipped`() {
        val owner = user()
        val a = singleItemOrder(owner, "a")
        val shipment = shipmentService.create(CreateShipmentRequest(orderIds = listOf(a.id), notes = null))
        orderService.submitForShipping(a.id, owner)
        shipmentService.update(shipment.id, UpdateShipmentRequest(status = ShipmentStatus.SHIPPED))
        val b = singleItemOrder(owner, "b")

        assertThrows(ResponseStatusException::class.java) { shipmentService.assignOrder(b.id, shipment.id) }
    }

    @Test
    fun `cannot combine an order into a shipment forced to shipped status while still open`() {
        // Simulates data from before shipment status was coupled to member-order readiness - a's
        // own status still says open, but its shipment has (somehow) already shipped.
        val owner = user()
        val a = singleItemOrder(owner, "a")
        val created = shipmentService.create(CreateShipmentRequest(orderIds = listOf(a.id), notes = null))
        val shipment = shipmentRepository.findById(created.id).get()
        shipment.status = ShipmentStatus.SHIPPED
        shipmentRepository.save(shipment)
        val b = singleItemOrder(owner, "b")

        assertThrows(ResponseStatusException::class.java) {
            shipmentService.create(CreateShipmentRequest(orderIds = listOf(a.id, b.id), notes = null))
        }
    }

    @Test
    fun `cannot mark a shipment shipped while a member order is not yet ready`() {
        val owner = user()
        val a = singleItemOrder(owner, "a")
        val shipment = shipmentService.create(CreateShipmentRequest(orderIds = listOf(a.id), notes = null))

        assertThrows(ResponseStatusException::class.java) {
            shipmentService.update(shipment.id, UpdateShipmentRequest(status = ShipmentStatus.SHIPPED))
        }
    }

    @Test
    fun `can mark a shipment shipped once every member order has reached preparing-shipping`() {
        val owner = user()
        val a = singleItemOrder(owner, "a")
        val shipment = shipmentService.create(CreateShipmentRequest(orderIds = listOf(a.id), notes = null))
        orderService.submitForShipping(a.id, owner)

        val updated = shipmentService.update(shipment.id, UpdateShipmentRequest(status = ShipmentStatus.SHIPPED))

        assertEquals(ShipmentStatus.SHIPPED, updated.status)
    }

    @Test
    fun `cancelling an order detaches it from its shipment, deleting the shipment once empty`() {
        val owner = user()
        val solo = singleItemOrder(owner, "solo")
        val shipment = shipmentService.create(CreateShipmentRequest(orderIds = listOf(solo.id), notes = null))

        val cancelled = orderService.cancelOwn(solo.id, owner)

        assertEquals(OrderStatus.CANCELLED, cancelled.status)
        assertNull(cancelled.shipmentId)
        assertFalse(shipmentRepository.existsById(shipment.id))
    }

    @Test
    fun `cancelling one of several orders on a shipment detaches only that order, keeping the shipment`() {
        val owner = user()
        val a = singleItemOrder(owner, "a")
        val b = singleItemOrder(owner, "b")
        val shipment = shipmentService.create(CreateShipmentRequest(orderIds = listOf(a.id, b.id), notes = null))

        orderService.cancelOwn(a.id, owner)

        assertTrue(shipmentRepository.existsById(shipment.id))
        assertNull(orderService.getById(a.id, owner).shipmentId)
        assertEquals(shipment.id, orderService.getById(b.id, owner).shipmentId)
    }

    @Test
    fun `admin cancelling via status update also detaches the order from its shipment`() {
        val owner = user()
        val solo = singleItemOrder(owner, "solo")
        val shipment = shipmentService.create(CreateShipmentRequest(orderIds = listOf(solo.id), notes = null))

        orderService.updateStatus(solo.id, OrderStatus.CANCELLED)

        assertFalse(shipmentRepository.existsById(shipment.id))
    }
}
