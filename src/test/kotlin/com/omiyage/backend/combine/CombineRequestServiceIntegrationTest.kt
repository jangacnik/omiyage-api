package com.omiyage.backend.combine

import com.omiyage.backend.PostgresIntegrationTest
import com.omiyage.backend.common.Condition
import com.omiyage.backend.common.Currency
import com.omiyage.backend.order.CreateOrderItemRequest
import com.omiyage.backend.order.CreateOrderRequest
import com.omiyage.backend.order.OrderService
import com.omiyage.backend.security.MeishiPrincipal
import com.omiyage.backend.security.Role
import com.omiyage.backend.shipment.CreateShipmentRequest
import com.omiyage.backend.shipment.ShipmentService
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
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
class CombineRequestServiceIntegrationTest : PostgresIntegrationTest() {

    @Autowired
    lateinit var orderService: OrderService

    @Autowired
    lateinit var shipmentService: ShipmentService

    @Autowired
    lateinit var combineRequestService: CombineRequestService

    @Autowired
    lateinit var entityManager: EntityManager

    private fun user() = MeishiPrincipal(UUID.randomUUID(), "bob", Role.USER)

    private fun order(principal: MeishiPrincipal) = orderService.create(
        principal,
        CreateOrderRequest(items = listOf(CreateOrderItemRequest(title = "A", condition = Condition.NEW, currency = Currency.JPY)), nickname = UUID.randomUUID().toString()),
    )

    // Every browsable target in this feature is an existing shipment (per the new "browse other
    // members' shipments to combine into" flow) - give an order one via the same combine()
    // primitive admin/member shipment creation already uses, rather than relying on submit-for-
    // shipping's side effect (which would also lock the order itself).
    private fun shipmentFor(order: com.omiyage.backend.order.OrderDto) =
        shipmentService.create(CreateShipmentRequest(orderIds = listOf(order.id)))

    @Test
    fun `accepting a request attaches only the requester's order onto the target shipment`() {
        val alice = user()
        val bob = user()
        val aliceOrder = order(alice)
        val bobOrder = order(bob)
        val bobOther = order(bob)
        val bobShipment = shipmentFor(bobOrder)

        val request = combineRequestService.create(alice, aliceOrder.id, bobShipment.id)
        val accepted = combineRequestService.accept(bob, request.id)

        assertEquals(CombineRequestStatus.ACCEPTED, accepted.status)
        assertEquals(bobShipment.id, accepted.resultingShipmentId)
        assertEquals(bobShipment.id, orderService.getById(aliceOrder.id, alice).shipmentId)
        assertEquals(bobShipment.id, orderService.getById(bobOrder.id, bob).shipmentId)
        assertEquals(null, orderService.getById(bobOther.id, bob).shipmentId)
    }

    @Test
    fun `only the shipment owner can accept or decline`() {
        val alice = user()
        val bob = user()
        val aliceOrder = order(alice)
        val bobShipment = shipmentFor(order(bob))
        val request = combineRequestService.create(alice, aliceOrder.id, bobShipment.id)

        assertThrows(ResponseStatusException::class.java) { combineRequestService.accept(alice, request.id) }
        assertThrows(ResponseStatusException::class.java) { combineRequestService.decline(alice, request.id) }
    }

    @Test
    fun `only the requester can cancel`() {
        val alice = user()
        val bob = user()
        val aliceOrder = order(alice)
        val bobShipment = shipmentFor(order(bob))
        val request = combineRequestService.create(alice, aliceOrder.id, bobShipment.id)

        assertThrows(ResponseStatusException::class.java) { combineRequestService.cancel(bob, request.id) }

        val cancelled = combineRequestService.cancel(alice, request.id)
        assertEquals(CombineRequestStatus.CANCELLED, cancelled.status)
    }

    @Test
    fun `cannot send a combine request when you already have an order on that shipment`() {
        val alice = user()
        val aliceOrder = order(alice)
        val aliceOtherOrder = order(alice)
        val aliceShipment = shipmentFor(aliceOtherOrder)

        assertThrows(ResponseStatusException::class.java) { combineRequestService.create(alice, aliceOrder.id, aliceShipment.id) }
    }

    @Test
    fun `accepting a second request cannot land the same member on a shipment twice`() {
        val alice = user()
        val bob = user()
        val aliceOrder1 = order(alice)
        val aliceOrder2 = order(alice)
        val bobShipment = shipmentFor(order(bob))

        // Both pass create()'s check, since neither of alice's orders is on bobShipment yet.
        val request1 = combineRequestService.create(alice, aliceOrder1.id, bobShipment.id)
        val request2 = combineRequestService.create(alice, aliceOrder2.id, bobShipment.id)

        combineRequestService.accept(bob, request1.id)
        // Flush + clear to drop the first-level cache - a real second accept() would arrive as
        // its own HTTP request with a fresh persistence context, not share this test's single
        // transaction and its already-loaded (now stale) Shipment.orders collection.
        entityManager.flush()
        entityManager.clear()
        assertThrows(ResponseStatusException::class.java) { combineRequestService.accept(bob, request2.id) }
    }

    @Test
    fun `a duplicate pending request for the same pair is rejected`() {
        val alice = user()
        val bob = user()
        val aliceOrder = order(alice)
        val bobShipment = shipmentFor(order(bob))
        combineRequestService.create(alice, aliceOrder.id, bobShipment.id)

        assertThrows(ResponseStatusException::class.java) { combineRequestService.create(alice, aliceOrder.id, bobShipment.id) }
    }

    @Test
    fun `a declined request stays in the requester's outgoing list for 14 days, invisible to others`() {
        val alice = user()
        val bob = user()
        val carol = user()
        val fresh = combineRequestService.create(alice, order(alice).id, shipmentFor(order(bob)).id)
        val old = combineRequestService.create(alice, order(alice).id, shipmentFor(order(bob)).id)
        val pending = combineRequestService.create(alice, order(alice).id, shipmentFor(order(bob)).id)
        val nearlyExpired = combineRequestService.create(alice, order(alice).id, shipmentFor(order(bob)).id)
        val justExpired = combineRequestService.create(alice, order(alice).id, shipmentFor(order(bob)).id)
        val accepted = combineRequestService.create(alice, order(alice).id, shipmentFor(order(bob)).id)
        val cancelled = combineRequestService.create(alice, order(alice).id, shipmentFor(order(bob)).id)
        listOf(fresh, old, nearlyExpired, justExpired).forEach { combineRequestService.decline(bob, it.id) }
        combineRequestService.accept(bob, accepted.id)
        combineRequestService.cancel(alice, cancelled.id)
        val now = java.time.OffsetDateTime.now()
        entityManager.find(CombineRequest::class.java, old.id).respondedAt = now.minusDays(15)
        entityManager.find(CombineRequest::class.java, nearlyExpired.id).respondedAt = now.minusDays(14).plusMinutes(1)
        entityManager.find(CombineRequest::class.java, justExpired.id).respondedAt = now.minusDays(14).minusMinutes(1)
        entityManager.flush()

        val outgoing = combineRequestService.listOutgoing(alice)

        assertEquals(
            setOf(
                fresh.id to CombineRequestStatus.DECLINED,
                nearlyExpired.id to CombineRequestStatus.DECLINED,
                pending.id to CombineRequestStatus.PENDING,
            ),
            outgoing.map { it.id to it.status }.toSet(),
        )
        assertTrue(outgoing.none { it.id == accepted.id || it.id == cancelled.id })
        assertNotNull(outgoing.single { it.id == fresh.id }.respondedAt)
        assertTrue(combineRequestService.listOutgoing(carol).isEmpty())
        assertTrue(combineRequestService.listOutgoing(bob).isEmpty())
    }

    @Test
    fun `a decided request can no longer be accepted`() {
        val alice = user()
        val bob = user()
        val aliceOrder = order(alice)
        val bobShipment = shipmentFor(order(bob))
        val request = combineRequestService.create(alice, aliceOrder.id, bobShipment.id)
        combineRequestService.decline(bob, request.id)

        assertThrows(ResponseStatusException::class.java) { combineRequestService.accept(bob, request.id) }
    }

    @Test
    fun `admin force-accept bypasses the shipment-owner check`() {
        val alice = user()
        val bob = user()
        val aliceOrder = order(alice)
        val bobShipment = shipmentFor(order(bob))
        val request = combineRequestService.create(alice, aliceOrder.id, bobShipment.id)

        val accepted = combineRequestService.forceAccept(request.id)

        assertEquals(CombineRequestStatus.ACCEPTED, accepted.status)
        assertNotNull(accepted.resultingShipmentId)
    }

    @Test
    fun `cannot request to combine into a shipment that has already shipped`() {
        val alice = user()
        val bob = user()
        val aliceOrder = order(alice)
        val bobOrder = order(bob)
        val bobShipment = shipmentFor(bobOrder)
        orderService.submitForShipping(bobOrder.id, bob)
        shipmentService.update(bobShipment.id, com.omiyage.backend.shipment.UpdateShipmentRequest(status = com.omiyage.backend.shipment.ShipmentStatus.SHIPPED))

        assertThrows(ResponseStatusException::class.java) { combineRequestService.create(alice, aliceOrder.id, bobShipment.id) }
    }
}
