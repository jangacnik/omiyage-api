package com.omiyage.backend.combine

import com.omiyage.backend.PostgresIntegrationTest
import com.omiyage.backend.common.Condition
import com.omiyage.backend.common.Currency
import com.omiyage.backend.order.CreateOrderItemRequest
import com.omiyage.backend.order.CreateOrderRequest
import com.omiyage.backend.order.OrderService
import com.omiyage.backend.security.MeishiPrincipal
import com.omiyage.backend.security.Role
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
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
    lateinit var combineRequestService: CombineRequestService

    private fun user() = MeishiPrincipal(UUID.randomUUID(), "bob", Role.USER)

    private fun order(principal: MeishiPrincipal) = orderService.create(
        principal,
        CreateOrderRequest(items = listOf(CreateOrderItemRequest(title = "A", condition = Condition.NEW, currency = Currency.JPY))),
    )

    @Test
    fun `accepting a request combines only the two named orders`() {
        val alice = user()
        val bob = user()
        val aliceOrder = order(alice)
        val bobOrder = order(bob)
        val untouched = order(bob)

        val request = combineRequestService.create(alice, aliceOrder.id, bobOrder.id)
        val accepted = combineRequestService.accept(bob, request.id)

        assertEquals(CombineRequestStatus.ACCEPTED, accepted.status)
        assertNotNull(accepted.resultingShipmentId)
        assertEquals(accepted.resultingShipmentId, orderService.getById(aliceOrder.id, alice).shipmentId)
        assertEquals(accepted.resultingShipmentId, orderService.getById(bobOrder.id, bob).shipmentId)
        assertEquals(null, orderService.getById(untouched.id, bob).shipmentId)
    }

    @Test
    fun `only the target can accept or decline`() {
        val alice = user()
        val bob = user()
        val aliceOrder = order(alice)
        val bobOrder = order(bob)
        val request = combineRequestService.create(alice, aliceOrder.id, bobOrder.id)

        assertThrows(ResponseStatusException::class.java) { combineRequestService.accept(alice, request.id) }
        assertThrows(ResponseStatusException::class.java) { combineRequestService.decline(alice, request.id) }
    }

    @Test
    fun `only the requester can cancel`() {
        val alice = user()
        val bob = user()
        val aliceOrder = order(alice)
        val bobOrder = order(bob)
        val request = combineRequestService.create(alice, aliceOrder.id, bobOrder.id)

        assertThrows(ResponseStatusException::class.java) { combineRequestService.cancel(bob, request.id) }

        val cancelled = combineRequestService.cancel(alice, request.id)
        assertEquals(CombineRequestStatus.CANCELLED, cancelled.status)
    }

    @Test
    fun `cannot send a combine request to your own order`() {
        val alice = user()
        val order1 = order(alice)
        val order2 = order(alice)

        assertThrows(ResponseStatusException::class.java) { combineRequestService.create(alice, order1.id, order2.id) }
    }

    @Test
    fun `a duplicate pending request for the same pair is rejected`() {
        val alice = user()
        val bob = user()
        val aliceOrder = order(alice)
        val bobOrder = order(bob)
        combineRequestService.create(alice, aliceOrder.id, bobOrder.id)

        assertThrows(ResponseStatusException::class.java) { combineRequestService.create(alice, aliceOrder.id, bobOrder.id) }
    }

    @Test
    fun `a decided request can no longer be accepted`() {
        val alice = user()
        val bob = user()
        val aliceOrder = order(alice)
        val bobOrder = order(bob)
        val request = combineRequestService.create(alice, aliceOrder.id, bobOrder.id)
        combineRequestService.decline(bob, request.id)

        assertThrows(ResponseStatusException::class.java) { combineRequestService.accept(bob, request.id) }
    }

    @Test
    fun `admin force-accept bypasses the target ownership check`() {
        val alice = user()
        val bob = user()
        val aliceOrder = order(alice)
        val bobOrder = order(bob)
        val request = combineRequestService.create(alice, aliceOrder.id, bobOrder.id)

        val accepted = combineRequestService.forceAccept(request.id)

        assertEquals(CombineRequestStatus.ACCEPTED, accepted.status)
        assertNotNull(accepted.resultingShipmentId)
    }
}
