package com.omiyage.backend.shipment

import com.omiyage.backend.PostgresIntegrationTest
import com.omiyage.backend.common.Condition
import com.omiyage.backend.common.Currency
import com.omiyage.backend.order.CreateOrderItemRequest
import com.omiyage.backend.order.CreateOrderRequest
import com.omiyage.backend.order.OrderItemStatus
import com.omiyage.backend.order.OrderService
import com.omiyage.backend.order.OrderStatus
import com.omiyage.backend.security.MeishiPrincipal
import com.omiyage.backend.security.Role
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
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
class ShipmentCombineSplitIntegrationTest : PostgresIntegrationTest() {

    @Autowired
    lateinit var orderService: OrderService

    @Autowired
    lateinit var shipmentService: ShipmentService

    @Autowired
    lateinit var shipmentRepository: ShipmentRepository

    private fun user() = MeishiPrincipal(UUID.randomUUID(), "bob", Role.USER)

    private fun order(principal: MeishiPrincipal) = orderService.create(
        principal,
        CreateOrderRequest(items = listOf(CreateOrderItemRequest(title = "A", condition = Condition.NEW, currency = Currency.JPY))),
    )

    @Test
    fun `combining two shipments with different users folds every order into one`() {
        val alice = user()
        val bob = user()
        val a1 = order(alice)
        val a2 = order(alice)
        val b1 = order(bob)
        val shipmentA = shipmentService.create(CreateShipmentRequest(orderIds = listOf(a1.id, a2.id)))
        val shipmentB = shipmentService.create(CreateShipmentRequest(orderIds = listOf(b1.id)))

        val combined = shipmentService.combineShipments(listOf(shipmentA.id, shipmentB.id), title = "Big box")

        assertEquals(3, combined.orders.size)
        assertEquals("Big box", combined.title)
        assertTrue(shipmentRepository.existsById(combined.id))
        assertFalse(shipmentRepository.existsById(if (combined.id == shipmentA.id) shipmentB.id else shipmentA.id))
    }

    @Test
    fun `combining fewer than two shipments is rejected`() {
        val a1 = order(user())
        val shipmentA = shipmentService.create(CreateShipmentRequest(orderIds = listOf(a1.id)))

        assertThrows(ResponseStatusException::class.java) { shipmentService.combineShipments(listOf(shipmentA.id), null) }
    }

    @Test
    fun `cannot combine a shipment that has already shipped`() {
        val owner = user()
        val a = order(owner)
        val shipment = shipmentService.create(CreateShipmentRequest(orderIds = listOf(a.id)))
        orderService.submitForShipping(a.id, owner)
        shipmentService.update(shipment.id, UpdateShipmentRequest(status = ShipmentStatus.SHIPPED))
        val b = order(user())
        val otherShipment = shipmentService.create(CreateShipmentRequest(orderIds = listOf(b.id)))

        assertThrows(ResponseStatusException::class.java) {
            shipmentService.combineShipments(listOf(shipment.id, otherShipment.id), null)
        }
    }

    @Test
    fun `splitting a shipment moves only the chosen orders onto a new one`() {
        val owner = user()
        val a = order(owner)
        val b = order(owner)
        val c = order(owner)
        val shipment = shipmentService.create(CreateShipmentRequest(orderIds = listOf(a.id, b.id, c.id)))

        val split = shipmentService.splitOff(shipment.id, listOf(a.id, b.id), title = "Split off")

        assertEquals(2, split.orders.size)
        assertEquals("Split off", split.title)
        assertEquals(1, shipmentRepository.findById(shipment.id).get().orders.size)
    }

    @Test
    fun `cannot split off every order, leaving the source empty`() {
        val owner = user()
        val a = order(owner)
        val b = order(owner)
        val shipment = shipmentService.create(CreateShipmentRequest(orderIds = listOf(a.id, b.id)))

        assertThrows(ResponseStatusException::class.java) {
            shipmentService.splitOff(shipment.id, listOf(a.id, b.id), null)
        }
    }

    @Test
    fun `marking a shipment shipped records shippedAt, and the owner can then mark it arrived`() {
        val owner = user()
        val a = order(owner)
        val shipment = shipmentService.create(CreateShipmentRequest(orderIds = listOf(a.id)))
        orderService.submitForShipping(a.id, owner)

        val shipped = shipmentService.update(shipment.id, UpdateShipmentRequest(status = ShipmentStatus.SHIPPED))
        assertNotNull(shipped.shippedAt)
        assertNull(shipped.arrivedAt)

        val arrived = shipmentService.markArrivedMine(shipment.id, owner.id)
        assertEquals(ShipmentStatus.DELIVERED, arrived.status)
        assertNotNull(arrived.arrivedAt)
    }

    @Test
    fun `marking a shipment shipped moves every non-cancelled order and its items to sent-shipped`() {
        val owner = user()
        val a = order(owner)
        val cancelled = order(owner)
        orderService.updateStatus(cancelled.id, OrderStatus.CANCELLED)
        val shipment = shipmentService.create(CreateShipmentRequest(orderIds = listOf(a.id)))
        orderService.submitForShipping(a.id, owner)
        shipmentService.assignOrder(cancelled.id, shipment.id)

        val shipped = shipmentService.update(shipment.id, UpdateShipmentRequest(status = ShipmentStatus.SHIPPED))

        val sentOrder = shipped.orders.single { it.id == a.id }
        assertEquals(OrderStatus.SENT, sentOrder.status)
        assertTrue(sentOrder.items.all { it.status == OrderItemStatus.SHIPPED })

        val cancelledOrder = shipped.orders.single { it.id == cancelled.id }
        assertEquals(OrderStatus.CANCELLED, cancelledOrder.status)
        assertTrue(cancelledOrder.items.none { it.status == OrderItemStatus.SHIPPED })
    }

    @Test
    fun `the shipment owner can set destination and shipping method while still preparing, but not after it ships`() {
        val owner = user()
        val other = user()
        val a = order(owner)
        val shipment = shipmentService.create(CreateShipmentRequest(orderIds = listOf(a.id)))

        assertThrows(ResponseStatusException::class.java) {
            shipmentService.updateShippingMine(shipment.id, other.id, ShipmentDestination.AUSTRIA, ShippingMethod.EMS)
        }

        val updated = shipmentService.updateShippingMine(shipment.id, owner.id, ShipmentDestination.AUSTRIA, ShippingMethod.EMS)
        assertEquals(ShipmentDestination.AUSTRIA, updated.destination)
        assertEquals(ShippingMethod.EMS, updated.shippingMethod)

        orderService.submitForShipping(a.id, owner)
        shipmentService.update(shipment.id, UpdateShipmentRequest(status = ShipmentStatus.SHIPPED))

        assertThrows(ResponseStatusException::class.java) {
            shipmentService.updateShippingMine(shipment.id, owner.id, ShipmentDestination.SLOVENIA, null)
        }
    }

    @Test
    fun `only the shipment owner can mark it arrived or rename it`() {
        val owner = user()
        val other = user()
        val a = order(owner)
        val shipment = shipmentService.create(CreateShipmentRequest(orderIds = listOf(a.id)))
        orderService.submitForShipping(a.id, owner)
        shipmentService.update(shipment.id, UpdateShipmentRequest(status = ShipmentStatus.SHIPPED))

        assertThrows(ResponseStatusException::class.java) { shipmentService.markArrivedMine(shipment.id, other.id) }
        assertThrows(ResponseStatusException::class.java) { shipmentService.renameMine(shipment.id, other.id, "Nope") }

        val renamed = shipmentService.renameMine(shipment.id, owner.id, "My gifts")
        assertEquals("My gifts", renamed.title)
    }

    @Test
    fun `browsable listing excludes the caller's own shipments and ones already shipped`() {
        val me = user()
        val other = user()
        val myOrder = order(me)
        val myShipment = shipmentService.create(CreateShipmentRequest(orderIds = listOf(myOrder.id)))
        val otherOpenOrder = order(other)
        val otherOpenShipment = shipmentService.create(CreateShipmentRequest(orderIds = listOf(otherOpenOrder.id), title = "Other's box"))
        val otherShippedOrder = order(other)
        val otherShippedShipment = shipmentService.create(CreateShipmentRequest(orderIds = listOf(otherShippedOrder.id)))
        orderService.submitForShipping(otherShippedOrder.id, other)
        shipmentService.update(otherShippedShipment.id, UpdateShipmentRequest(status = ShipmentStatus.SHIPPED))

        val browsable = shipmentService.browsableForMember(me.id, "Bearer irrelevant")

        val browsableIds = browsable.map { it.id }
        assertFalse(browsableIds.contains(myShipment.id))
        assertTrue(browsableIds.contains(otherOpenShipment.id))
        assertFalse(browsableIds.contains(otherShippedShipment.id))
        val entry = browsable.first { it.id == otherOpenShipment.id }
        assertEquals("Other's box", entry.title)
        assertEquals(1, entry.itemCount)
    }

    @Test
    fun `cannot mark a shipment arrived before it has shipped`() {
        val owner = user()
        val a = order(owner)
        val shipment = shipmentService.create(CreateShipmentRequest(orderIds = listOf(a.id)))

        assertThrows(ResponseStatusException::class.java) { shipmentService.markArrivedMine(shipment.id, owner.id) }
    }
}
