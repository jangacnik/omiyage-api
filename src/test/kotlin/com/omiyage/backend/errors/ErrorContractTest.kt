package com.omiyage.backend.errors

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.omiyage.backend.PostgresIntegrationTest
import com.omiyage.backend.catalog.InventoryItemRequest
import com.omiyage.backend.common.Condition
import com.omiyage.backend.common.Currency
import com.omiyage.backend.order.CreateOrderItemRequest
import com.omiyage.backend.order.CreateOrderRequest
import com.omiyage.backend.order.OrderStatus
import com.omiyage.backend.order.UpdateOrderStatusRequest
import com.omiyage.backend.security.TestJwt
import com.omiyage.backend.shipment.CreateShipmentRequest
import com.omiyage.backend.shipment.ShipmentRepository
import com.omiyage.backend.shipment.ShipmentStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

private const val TEST_SECRET = "integration-test-secret"

class Caller(val id: UUID, val token: String)

/** Pins every error response of the HTTP API (status, `error` message and `code`) through the real
 *  security chain, services and Postgres, so the response contract survives any change to how the
 *  services signal expected failures. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
abstract class ErrorContractTest : PostgresIntegrationTest() {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @Autowired
    lateinit var shipments: ShipmentRepository

    protected fun newCaller(role: String = "USER"): Caller {
        val id = UUID.randomUUID()
        return Caller(id, TestJwt.sign(TEST_SECRET, subject = id.toString(), nickname = "n-${id.toString().take(8)}", role = role))
    }

    protected fun send(who: Caller, method: HttpMethod, path: String, body: Any? = null): MvcResult {
        val builder = request(method, path).header("Authorization", "Bearer ${who.token}")
        if (body != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body))
        }
        return mockMvc.perform(builder).andReturn()
    }

    protected fun json(result: MvcResult): JsonNode = objectMapper.readTree(result.response.contentAsString)

    protected fun assertError(result: MvcResult, status: Int, code: String, message: String) {
        assertEquals(status, result.response.status)
        val body = json(result)
        assertEquals(message, body.get("error").asText())
        assertEquals(code, body.get("code").asText())
        assertEquals(2, body.size())
    }

    protected fun assertNotFound(result: MvcResult, message: String) = assertError(result, 404, "not_found", message)

    protected fun assertConflict(result: MvcResult, message: String) = assertError(result, 409, "conflict", message)

    protected fun assertBadRequest(result: MvcResult, message: String) = assertError(result, 400, "bad_request", message)

    protected fun assertForbidden(result: MvcResult, message: String) = assertError(result, 403, "forbidden", message)

    protected fun uuidOf(node: JsonNode, field: String = "id"): UUID = UUID.fromString(node.get(field).asText())

    protected fun orderItemIds(order: JsonNode): List<UUID> = order.get("items").map { uuidOf(it) }

    protected fun uniqueTitle(): String = "item-${UUID.randomUUID()}"

    protected fun createInventoryItem(admin: Caller, preorder: Boolean = false, link: String? = null, title: String = uniqueTitle()): UUID {
        val body = InventoryItemRequest(
            title = title,
            description = null,
            link = link,
            condition = Condition.NEW,
            priceAmount = BigDecimal("10.00"),
            currency = Currency.EUR,
            stockQuantity = 5,
            isPreorder = preorder,
            expectedReleaseDate = if (preorder) LocalDate.now().plusDays(30) else null,
        )
        val result = send(admin, HttpMethod.POST, "/api/admin/inventory", body)
        assertEquals(201, result.response.status)
        return uuidOf(json(result))
    }

    protected fun createOrder(owner: Caller, itemCount: Int = 1): JsonNode {
        val items = (1..itemCount).map { CreateOrderItemRequest(title = uniqueTitle(), condition = Condition.NEW, currency = Currency.EUR) }
        val result = send(owner, HttpMethod.POST, "/api/orders", CreateOrderRequest(items = items))
        assertEquals(201, result.response.status)
        return json(result)
    }

    protected fun createOrderFromInventory(owner: Caller, inventoryItemId: UUID): JsonNode {
        val items = listOf(CreateOrderItemRequest(inventoryItemId = inventoryItemId, currency = Currency.EUR))
        val result = send(owner, HttpMethod.POST, "/api/orders", CreateOrderRequest(items = items))
        assertEquals(201, result.response.status)
        return json(result)
    }

    protected fun setOrderStatus(admin: Caller, orderId: UUID, status: OrderStatus) {
        val result = send(admin, HttpMethod.PATCH, "/api/admin/orders/$orderId", UpdateOrderStatusRequest(status))
        assertEquals(200, result.response.status)
    }

    protected fun createShipment(admin: Caller, vararg orderIds: UUID): UUID {
        val result = send(admin, HttpMethod.POST, "/api/admin/shipments", CreateShipmentRequest(orderIds = orderIds.toList()))
        assertEquals(201, result.response.status)
        return uuidOf(json(result))
    }

    protected fun forceShipmentStatus(shipmentId: UUID, status: ShipmentStatus) {
        val shipment = shipments.findById(shipmentId).get()
        shipment.status = status
        shipments.saveAndFlush(shipment)
    }
}
