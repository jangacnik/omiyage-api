package com.omiyage.backend.order

import com.omiyage.backend.PostgresIntegrationTest
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
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@SpringBootTest
@Transactional
class OrderSplitIntegrationTest : PostgresIntegrationTest() {

    @Autowired
    lateinit var orderService: OrderService

    @Autowired
    lateinit var shipmentService: ShipmentService

    @Autowired
    lateinit var shipmentRepository: ShipmentRepository

    private fun user() = MeishiPrincipal(UUID.randomUUID(), "bob", Role.USER)

    private fun twoItemOrder(principal: MeishiPrincipal) = orderService.create(
        principal,
        CreateOrderRequest(
            items = listOf(
                CreateOrderItemRequest(title = "A", condition = Condition.NEW, currency = Currency.JPY),
                CreateOrderItemRequest(title = "B", condition = Condition.NEW, currency = Currency.JPY),
            ),
        ),
    )

    @Test
    fun `splitting moves only the chosen items onto a new order`() {
        val owner = user()
        val order = twoItemOrder(owner)

        val result = orderService.splitMine(owner, order.id, listOf(order.items[0].id))

        assertEquals(1, result.original.items.size)
        assertEquals("B", result.original.items[0].title)
        assertEquals(1, result.created.items.size)
        assertEquals("A", result.created.items[0].title)
        assertEquals(owner.id, result.created.userId)
        assertNull(result.created.shipmentId)
    }

    @Test
    fun `cannot split all items off, leaving the original order empty`() {
        val owner = user()
        val order = twoItemOrder(owner)

        assertThrows(ResponseStatusException::class.java) {
            orderService.splitMine(owner, order.id, order.items.map { it.id })
        }
    }

    @Test
    fun `cannot split someone else's order`() {
        val owner = user()
        val order = twoItemOrder(owner)

        assertThrows(ResponseStatusException::class.java) {
            orderService.splitMine(user(), order.id, listOf(order.items[0].id))
        }
    }

    @Test
    fun `splitting an item out of a combined shipment leaves the new order unattached`() {
        val owner = user()
        val order = twoItemOrder(owner)
        shipmentService.create(CreateShipmentRequest(orderIds = listOf(order.id)))

        val result = orderService.splitMine(owner, order.id, listOf(order.items[0].id))

        assertNull(result.created.shipmentId)
        assertEquals(order.id, orderService.getById(order.id, owner).id)
    }

    @Test
    fun `a member can leave a shipment while it is still preparing`() {
        val owner = user()
        val a = twoItemOrder(owner)
        val b = twoItemOrder(owner)
        val shipment = shipmentService.create(CreateShipmentRequest(orderIds = listOf(a.id, b.id)))

        shipmentService.leaveShipment(a.id, owner)

        assertNull(orderService.getById(a.id, owner).shipmentId)
        assertTrue(shipmentRepository.existsById(shipment.id))
    }

    @Test
    fun `a member cannot leave someone else's order off a shipment`() {
        val owner = user()
        val other = user()
        val a = twoItemOrder(owner)
        shipmentService.create(CreateShipmentRequest(orderIds = listOf(a.id)))

        assertThrows(ResponseStatusException::class.java) { shipmentService.leaveShipment(a.id, other) }
    }

    @Test
    fun `a member cannot leave a shipment that has already shipped`() {
        val owner = user()
        val a = twoItemOrder(owner)
        val shipment = shipmentService.create(CreateShipmentRequest(orderIds = listOf(a.id)))
        orderService.submitForShipping(a.id, owner)
        shipmentService.update(shipment.id, com.omiyage.backend.shipment.UpdateShipmentRequest(status = com.omiyage.backend.shipment.ShipmentStatus.SHIPPED))

        assertThrows(ResponseStatusException::class.java) { shipmentService.leaveShipment(a.id, owner) }
    }
}
