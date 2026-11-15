package com.omiyage.backend.errors

import com.omiyage.backend.combine.CreateCombineRequestRequest
import com.omiyage.backend.order.OrderStatus
import com.omiyage.backend.shipment.ShipmentStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import java.util.UUID

private const val BASE = "/api/orders/combine-requests"

class CombineErrorContractTest : ErrorContractTest() {

    private val owner = newCaller()
    private val requester = newCaller()
    private val admin = newCaller("ADMIN")

    private val targetShipmentId: UUID by lazy { createShipment(admin, uuidOf(createOrder(owner))) }

    private fun orderOf(who: Caller): UUID = uuidOf(createOrder(who))

    private fun request(orderId: UUID, shipmentId: UUID = targetShipmentId, who: Caller = requester) =
        send(who, HttpMethod.POST, BASE, CreateCombineRequestRequest(orderId, shipmentId))

    private fun pendingRequest(orderId: UUID = orderOf(requester)): UUID {
        val result = request(orderId)
        assertEquals(201, result.response.status)
        return uuidOf(json(result))
    }

    @Test
    fun `requesting with someone else's order is 404`() {
        assertNotFound(request(orderOf(owner)), "order not found")
    }

    @Test
    fun `requesting into an unknown shipment is 404`() {
        assertNotFound(request(orderOf(requester), shipmentId = UUID.randomUUID()), "shipment not found")
    }

    @Test
    fun `requesting into a shipment holding an own order is 400`() {
        val second = orderOf(owner)
        assertBadRequest(request(second, who = owner), "you already have an order on this shipment")
    }

    @Test
    fun `requesting with a closed order is 409`() {
        val orderId = orderOf(requester)
        setOrderStatus(admin, orderId, OrderStatus.CANCELLED)
        assertConflict(request(orderId), "an order that is no longer open cannot be combined")
    }

    @Test
    fun `requesting with an order whose shipment already shipped is 409`() {
        val orderId = orderOf(requester)
        forceShipmentStatus(createShipment(admin, orderId), ShipmentStatus.SHIPPED)
        assertConflict(request(orderId), "an order that has already shipped cannot be combined")
    }

    @Test
    fun `requesting into a shipped shipment is 409`() {
        forceShipmentStatus(targetShipmentId, ShipmentStatus.SHIPPED)
        assertConflict(request(orderOf(requester)), "a shipment that has already shipped cannot be combined into")
    }

    @Test
    fun `a second pending request for the same pair is 409`() {
        val orderId = orderOf(requester)
        pendingRequest(orderId)
        assertConflict(request(orderId), "a pending combine request already exists for this order and shipment")
    }

    @Test
    fun `accepting an unknown request is 404`() {
        assertNotFound(send(owner, HttpMethod.POST, "$BASE/${UUID.randomUUID()}/accept"), "combine request not found")
    }

    @Test
    fun `accepting a request that is no longer pending is 409`() {
        val requestId = pendingRequest()
        assertEquals(200, send(owner, HttpMethod.POST, "$BASE/$requestId/decline").response.status)
        assertConflict(send(owner, HttpMethod.POST, "$BASE/$requestId/accept"), "combine request is no longer pending")
    }

    @Test
    fun `accepting as someone who does not own the target is 404`() {
        val requestId = pendingRequest()
        assertNotFound(send(requester, HttpMethod.POST, "$BASE/$requestId/accept"), "combine request not found")
    }

    @Test
    fun `declining as someone who does not own the target is 404`() {
        val requestId = pendingRequest()
        assertNotFound(send(requester, HttpMethod.POST, "$BASE/$requestId/decline"), "combine request not found")
    }

    @Test
    fun `cancelling as someone who is not the requester is 404`() {
        val requestId = pendingRequest()
        assertNotFound(send(owner, HttpMethod.DELETE, "$BASE/$requestId"), "combine request not found")
    }

    @Test
    fun `accepting after the order was closed is 409`() {
        val orderId = orderOf(requester)
        val requestId = pendingRequest(orderId)
        setOrderStatus(admin, orderId, OrderStatus.CANCELLED)
        assertConflict(send(owner, HttpMethod.POST, "$BASE/$requestId/accept"), "an order that is no longer open cannot be combined")
    }

    @Test
    fun `accepting after the target shipped is 409`() {
        val requestId = pendingRequest()
        forceShipmentStatus(targetShipmentId, ShipmentStatus.SHIPPED)
        assertConflict(send(owner, HttpMethod.POST, "$BASE/$requestId/accept"), "a shipment that has already shipped cannot be combined into")
    }

    @Test
    fun `accepting a second request from the same member is 409`() {
        val first = pendingRequest()
        val second = pendingRequest()
        assertEquals(200, send(owner, HttpMethod.POST, "$BASE/$first/accept").response.status)
        assertConflict(send(owner, HttpMethod.POST, "$BASE/$second/accept"), "that member already has an order on this shipment")
    }

    @Test
    fun `force-accepting an unknown request is 404`() {
        assertNotFound(send(admin, HttpMethod.POST, "/api/admin/combine-requests/${UUID.randomUUID()}/force-accept"), "combine request not found")
    }
}
