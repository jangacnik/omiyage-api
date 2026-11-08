package com.omiyage.backend.errors

import com.omiyage.backend.order.OrderStatus
import com.omiyage.backend.shipment.AssignShipmentRequest
import com.omiyage.backend.shipment.CombineShipmentsRequest
import com.omiyage.backend.shipment.CreateShipmentRequest
import com.omiyage.backend.shipment.RenameShipmentRequest
import com.omiyage.backend.shipment.Shipment
import com.omiyage.backend.shipment.ShipmentDestination
import com.omiyage.backend.shipment.ShipmentStatus
import com.omiyage.backend.shipment.ShippingMethod
import com.omiyage.backend.shipment.ShippingRateRequest
import com.omiyage.backend.shipment.SplitShipmentRequest
import com.omiyage.backend.shipment.UpdateShipmentRequest
import com.omiyage.backend.shipment.UpdateShippingRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.test.web.servlet.MvcResult
import java.util.UUID

class ShipmentErrorContractTest : ErrorContractTest() {

    private val user = newCaller()
    private val other = newCaller()
    private val admin = newCaller("ADMIN")

    private fun newOrder(owner: Caller): UUID = uuidOf(createOrder(owner))

    private fun rate(maxWeightGrams: Int) =
        ShippingRateRequest(ShipmentDestination.SLOVENIA, ShippingMethod.EMS, maxWeightGrams = maxWeightGrams, costJpy = 1000, estimatedDays = 5)

    private fun createRate(maxWeightGrams: Int): UUID {
        val result = send(admin, HttpMethod.POST, "/api/admin/shipping-rates", rate(maxWeightGrams))
        assertEquals(201, result.response.status)
        return uuidOf(json(result))
    }

    private fun mine(who: Caller, method: HttpMethod, path: String, body: Any? = null): MvcResult = send(who, method, "/api/shipments/mine/$path", body)

    @Test
    fun `reading an unknown shipment is 404`() {
        assertNotFound(send(admin, HttpMethod.GET, "/api/admin/shipments/${UUID.randomUUID()}"), "shipment not found")
    }

    @Test
    fun `combining an unknown order is 404`() {
        val missing = UUID.randomUUID()
        assertNotFound(send(admin, HttpMethod.POST, "/api/admin/shipments", CreateShipmentRequest(orderIds = listOf(missing))), "order not found: $missing")
    }

    @Test
    fun `combining a closed order is 409`() {
        val orderId = newOrder(user)
        setOrderStatus(admin, orderId, OrderStatus.CANCELLED)
        assertConflict(
            send(admin, HttpMethod.POST, "/api/admin/shipments", CreateShipmentRequest(orderIds = listOf(orderId))),
            "an order that is no longer open cannot be combined into a shipment",
        )
    }

    @Test
    fun `combining an order whose shipment already shipped is 409`() {
        val orderId = newOrder(user)
        forceShipmentStatus(createShipment(admin, orderId), ShipmentStatus.SHIPPED)
        assertConflict(
            send(admin, HttpMethod.POST, "/api/admin/shipments", CreateShipmentRequest(orderIds = listOf(orderId))),
            "an order that has already shipped cannot be combined into another shipment",
        )
    }

    @Test
    fun `combining shipments that hold no orders is 400`() {
        val first = shipments.saveAndFlush(Shipment(ownerUserId = user.id)).id!!
        val second = shipments.saveAndFlush(Shipment(ownerUserId = user.id)).id!!
        assertBadRequest(send(admin, HttpMethod.POST, "/api/admin/shipments/combine", CombineShipmentsRequest(listOf(first, second))), "select at least one order to combine")
    }

    @Test
    fun `combining fewer than two shipments is 400`() {
        val shipmentId = createShipment(admin, newOrder(user))
        assertBadRequest(send(admin, HttpMethod.POST, "/api/admin/shipments/combine", CombineShipmentsRequest(listOf(shipmentId))), "select at least two shipments to combine")
    }

    @Test
    fun `combining an unknown shipment is 404`() {
        val shipmentId = createShipment(admin, newOrder(user))
        val result = send(admin, HttpMethod.POST, "/api/admin/shipments/combine", CombineShipmentsRequest(listOf(shipmentId, UUID.randomUUID())))
        assertNotFound(result, "shipment not found")
    }

    @Test
    fun `combining a shipped shipment is 409`() {
        val first = createShipment(admin, newOrder(user))
        val second = createShipment(admin, newOrder(other))
        forceShipmentStatus(first, ShipmentStatus.SHIPPED)
        assertConflict(
            send(admin, HttpMethod.POST, "/api/admin/shipments/combine", CombineShipmentsRequest(listOf(first, second))),
            "a shipment that has already shipped cannot be combined",
        )
    }

    @Test
    fun `splitting a shipped shipment is 409`() {
        val first = newOrder(user)
        val shipmentId = createShipment(admin, first, newOrder(user))
        forceShipmentStatus(shipmentId, ShipmentStatus.SHIPPED)
        assertConflict(send(admin, HttpMethod.POST, "/api/admin/shipments/$shipmentId/split", SplitShipmentRequest(listOf(first))), "a shipment that has already shipped cannot be split")
    }

    @Test
    fun `splitting off an order that is not on the shipment is 400`() {
        val shipmentId = createShipment(admin, newOrder(user), newOrder(user))
        val stranger = newOrder(other)
        assertBadRequest(
            send(admin, HttpMethod.POST, "/api/admin/shipments/$shipmentId/split", SplitShipmentRequest(listOf(stranger))),
            "order $stranger does not belong to this shipment",
        )
    }

    @Test
    fun `splitting off every order is 400`() {
        val first = newOrder(user)
        val second = newOrder(user)
        val shipmentId = createShipment(admin, first, second)
        assertBadRequest(
            send(admin, HttpMethod.POST, "/api/admin/shipments/$shipmentId/split", SplitShipmentRequest(listOf(first, second))),
            "split must move at least one order and leave at least one behind",
        )
    }

    @Test
    fun `shipping a shipment whose orders are not ready is 409`() {
        val shipmentId = createShipment(admin, newOrder(user))
        assertConflict(
            send(admin, HttpMethod.PATCH, "/api/admin/shipments/$shipmentId", UpdateShipmentRequest(status = ShipmentStatus.SHIPPED)),
            "every order in this shipment must reach preparing-shipping before it can ship",
        )
    }

    @Test
    fun `updating an unknown shipment is 404`() {
        assertNotFound(send(admin, HttpMethod.PATCH, "/api/admin/shipments/${UUID.randomUUID()}", UpdateShipmentRequest(notes = "n")), "shipment not found")
    }

    @Test
    fun `deleting an unknown shipment is 404`() {
        assertNotFound(send(admin, HttpMethod.DELETE, "/api/admin/shipments/${UUID.randomUUID()}"), "shipment not found")
    }

    @Test
    fun `marking an unknown shipment paid is 404`() {
        val result = send(admin, HttpMethod.PATCH, "/api/admin/shipments/${UUID.randomUUID()}/paid", com.omiyage.backend.order.MarkItemPaidRequest(true))
        assertNotFound(result, "shipment not found")
    }

    @Test
    fun `assigning an unknown order is 404`() {
        assertNotFound(send(admin, HttpMethod.PATCH, "/api/admin/orders/${UUID.randomUUID()}/shipment", AssignShipmentRequest(null)), "order not found")
    }

    @Test
    fun `assigning an order to an unknown shipment is 404`() {
        val result = send(admin, HttpMethod.PATCH, "/api/admin/orders/${newOrder(user)}/shipment", AssignShipmentRequest(UUID.randomUUID()))
        assertNotFound(result, "shipment not found")
    }

    @Test
    fun `assigning an order to a shipped shipment is 409`() {
        val shipmentId = createShipment(admin, newOrder(user))
        forceShipmentStatus(shipmentId, ShipmentStatus.SHIPPED)
        val result = send(admin, HttpMethod.PATCH, "/api/admin/orders/${newOrder(other)}/shipment", AssignShipmentRequest(shipmentId))
        assertConflict(result, "cannot attach an order to a shipment that has already shipped")
    }

    @Test
    fun `a member reading a shipment without an own order is 404`() {
        val shipmentId = createShipment(admin, newOrder(other))
        assertNotFound(mine(user, HttpMethod.GET, "$shipmentId"), "shipment not found")
    }

    @Test
    fun `a member reading an unknown shipment is 404`() {
        assertNotFound(mine(user, HttpMethod.GET, "${UUID.randomUUID()}"), "shipment not found")
    }

    @Test
    fun `renaming a shipment as a non-owner is 403`() {
        val shipmentId = createShipment(admin, newOrder(user))
        assertForbidden(mine(other, HttpMethod.PATCH, "$shipmentId/title", RenameShipmentRequest("mine")), "only the shipment owner can rename it")
    }

    @Test
    fun `changing shipping as a non-owner is 403`() {
        val shipmentId = createShipment(admin, newOrder(user))
        assertForbidden(
            mine(other, HttpMethod.PATCH, "$shipmentId/shipping", UpdateShippingRequest(ShipmentDestination.SLOVENIA, ShippingMethod.EMS)),
            "only the shipment owner can set its destination and shipping method",
        )
    }

    @Test
    fun `changing shipping after the shipment shipped is 409`() {
        val shipmentId = createShipment(admin, newOrder(user))
        forceShipmentStatus(shipmentId, ShipmentStatus.SHIPPED)
        assertConflict(
            mine(user, HttpMethod.PATCH, "$shipmentId/shipping", UpdateShippingRequest(ShipmentDestination.SLOVENIA, ShippingMethod.EMS)),
            "cannot change destination or shipping method once a shipment has shipped",
        )
    }

    @Test
    fun `marking arrival as a non-owner is 403`() {
        val shipmentId = createShipment(admin, newOrder(user))
        assertForbidden(mine(other, HttpMethod.POST, "$shipmentId/mark-arrived"), "only the shipment owner can mark it as arrived")
    }

    @Test
    fun `marking arrival before the shipment shipped is 409`() {
        val shipmentId = createShipment(admin, newOrder(user))
        assertConflict(mine(user, HttpMethod.POST, "$shipmentId/mark-arrived"), "only a shipped shipment can be marked as arrived")
    }

    @Test
    fun `leaving with someone else's order is 404`() {
        val orderId = newOrder(other)
        assertNotFound(send(user, HttpMethod.POST, "/api/orders/$orderId/leave-shipment"), "order not found")
    }

    @Test
    fun `leaving without a shipment is 409`() {
        val orderId = newOrder(user)
        assertConflict(send(user, HttpMethod.POST, "/api/orders/$orderId/leave-shipment"), "order is not part of a shipment")
    }

    @Test
    fun `leaving a shipped shipment is 409`() {
        val orderId = newOrder(user)
        forceShipmentStatus(createShipment(admin, orderId), ShipmentStatus.SHIPPED)
        assertConflict(send(user, HttpMethod.POST, "/api/orders/$orderId/leave-shipment"), "cannot leave a shipment that has already shipped")
    }

    @Test
    fun `a duplicate shipping rate is 409`() {
        createRate(500)
        assertConflict(send(admin, HttpMethod.POST, "/api/admin/shipping-rates", rate(500)), "a rate already exists for this destination, method and weight tier")
    }

    @Test
    fun `updating a shipping rate onto a duplicate is 409`() {
        createRate(500)
        val second = createRate(1000)
        assertConflict(send(admin, HttpMethod.PATCH, "/api/admin/shipping-rates/$second", rate(500)), "a rate already exists for this destination, method and weight tier")
    }

    @Test
    fun `updating an unknown shipping rate is 404`() {
        assertNotFound(send(admin, HttpMethod.PATCH, "/api/admin/shipping-rates/${UUID.randomUUID()}", rate(500)), "shipping rate not found")
    }

    @Test
    fun `deleting an unknown shipping rate is 404`() {
        assertNotFound(send(admin, HttpMethod.DELETE, "/api/admin/shipping-rates/${UUID.randomUUID()}"), "shipping rate not found")
    }
}
