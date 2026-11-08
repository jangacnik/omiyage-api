package com.omiyage.backend.errors

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.omiyage.backend.PostgresIntegrationTest
import com.omiyage.backend.basket.AddBasketLineRequest
import com.omiyage.backend.basket.AddBasketToOrderRequest
import com.omiyage.backend.combine.CreateCombineRequestRequest
import com.omiyage.backend.shipment.CreateShipmentRequest
import org.junit.jupiter.api.AfterEach
import org.springframework.jdbc.core.JdbcTemplate
import com.omiyage.backend.catalog.InventoryItemRequest
import com.omiyage.backend.common.Condition
import com.omiyage.backend.common.Currency
import com.omiyage.backend.order.CreateOrderItemRequest
import com.omiyage.backend.order.CreateOrderRequest
import com.omiyage.backend.security.TestJwt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import java.math.BigDecimal
import java.util.UUID

/** A failed request must leave no partial writes behind. Not @Transactional on purpose: the
 *  service transactions here are the real, committing ones. */
@SpringBootTest
@AutoConfigureMockMvc
class FailureRollbackContractTest : PostgresIntegrationTest() {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @Autowired
    lateinit var jdbc: JdbcTemplate

    private val createdUsers = mutableListOf<UUID>()

    // The orders, shipments and requests made here are committed, and other suites count rows across the shared database.
    @AfterEach
    fun deleteCommittedRows() {
        createdUsers.forEach { id ->
            jdbc.update(
                "DELETE FROM combine_requests WHERE requester_order_id IN (SELECT id FROM orders WHERE user_id = ?) " +
                    "OR target_shipment_id IN (SELECT id FROM shipments WHERE owner_user_id = ?)",
                id,
                id,
            )
            jdbc.update("DELETE FROM order_items WHERE order_id IN (SELECT id FROM orders WHERE user_id = ?)", id)
            jdbc.update("DELETE FROM orders WHERE user_id = ?", id)
            jdbc.update("DELETE FROM shipments WHERE owner_user_id = ?", id)
            jdbc.update("DELETE FROM basket_lines WHERE user_id = ?", id)
        }
    }

    private fun token(role: String): Pair<UUID, String> {
        val id = UUID.randomUUID()
        createdUsers.add(id)
        return id to TestJwt.sign("integration-test-secret", subject = id.toString(), nickname = "n-${id.toString().take(8)}", role = role)
    }

    private val admin = token("ADMIN").second
    private val user = token("USER").second

    private fun send(token: String, method: HttpMethod, path: String, body: Any? = null): MvcResult {
        val builder = request(method, path).header("Authorization", "Bearer $token")
        if (body != null) builder.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body))
        return mockMvc.perform(builder).andReturn()
    }

    private fun json(result: MvcResult): JsonNode = objectMapper.readTree(result.response.contentAsString)

    private fun createInventoryItem(stock: Int): UUID {
        val body = InventoryItemRequest(
            title = "rollback-${UUID.randomUUID()}",
            description = null,
            condition = Condition.NEW,
            priceAmount = BigDecimal("10.00"),
            currency = Currency.EUR,
            stockQuantity = stock,
        )
        val result = send(admin, HttpMethod.POST, "/api/admin/inventory", body)
        assertEquals(201, result.response.status)
        return UUID.fromString(json(result).get("id").asText())
    }

    private fun stockOf(itemId: UUID): Int {
        val result = send(admin, HttpMethod.GET, "/api/inventory/$itemId")
        assertEquals(200, result.response.status)
        return json(result).get("stockQuantity").asInt()
    }

    @Test
    fun `an order that fails on its second item reserves no stock for the first`() {
        val itemId = createInventoryItem(stock = 5)
        val items = listOf(
            CreateOrderItemRequest(inventoryItemId = itemId, currency = Currency.EUR, quantity = 2),
            CreateOrderItemRequest(inventoryItemId = UUID.randomUUID(), currency = Currency.EUR),
        )

        val result = send(user, HttpMethod.POST, "/api/orders", CreateOrderRequest(items = items))

        assertEquals(404, result.response.status)
        assertEquals(5, stockOf(itemId))
    }

    @Test
    fun `a basket that fails to confirm keeps its lines and creates no order`() {
        val goodItemId = createInventoryItem(stock = 5)
        val goneItemId = createInventoryItem(stock = 5)
        assertEquals(201, send(user, HttpMethod.POST, "/api/basket/lines", AddBasketLineRequest(inventoryItemId = goodItemId, currency = Currency.EUR, quantity = 2)).response.status)
        assertEquals(201, send(user, HttpMethod.POST, "/api/basket/lines", AddBasketLineRequest(inventoryItemId = goneItemId, currency = Currency.EUR)).response.status)
        assertEquals(204, send(admin, HttpMethod.DELETE, "/api/admin/inventory/$goneItemId").response.status)

        val result = send(user, HttpMethod.POST, "/api/basket/confirm")

        assertEquals(404, result.response.status)
        assertEquals(5, stockOf(goodItemId))
        assertEquals(2, json(send(user, HttpMethod.GET, "/api/basket")).size())
        assertEquals(0, json(send(user, HttpMethod.GET, "/api/orders/mine")).get("content").size())
    }

    private fun createOrder(token: String): JsonNode {
        val items = listOf(CreateOrderItemRequest(title = "rollback-${UUID.randomUUID()}", condition = Condition.NEW, currency = Currency.EUR))
        val result = send(token, HttpMethod.POST, "/api/orders", CreateOrderRequest(items = items))
        assertEquals(201, result.response.status)
        return json(result)
    }

    private fun idOf(node: JsonNode) = UUID.fromString(node.get("id").asText())

    @Test
    fun `a basket that fails to join an order adds nothing to it and keeps its lines`() {
        val orderId = idOf(createOrder(user))
        val goneItemId = createInventoryItem(stock = 5)
        assertEquals(201, send(user, HttpMethod.POST, "/api/basket/lines", AddBasketLineRequest(customTitle = "extra-${UUID.randomUUID()}", currency = Currency.EUR)).response.status)
        assertEquals(201, send(user, HttpMethod.POST, "/api/basket/lines", AddBasketLineRequest(inventoryItemId = goneItemId, currency = Currency.EUR)).response.status)
        assertEquals(204, send(admin, HttpMethod.DELETE, "/api/admin/inventory/$goneItemId").response.status)

        val result = send(user, HttpMethod.POST, "/api/basket/add-to-order", AddBasketToOrderRequest(orderId))

        assertEquals(404, result.response.status)
        assertEquals(1, json(send(user, HttpMethod.GET, "/api/orders/$orderId")).get("items").size())
        assertEquals(2, json(send(user, HttpMethod.GET, "/api/basket")).size())
    }

    @Test
    fun `a rejected combine request acceptance leaves the accepted one and the pending one as they were`() {
        val owner = token("USER").second
        val requester = token("USER").second
        val targetShipmentId = idOf(
            json(send(admin, HttpMethod.POST, "/api/admin/shipments", CreateShipmentRequest(orderIds = listOf(idOf(createOrder(owner))))).also { assertEquals(201, it.response.status) }),
        )
        val firstOrderId = idOf(createOrder(requester))
        val secondOrderId = idOf(createOrder(requester))
        val firstRequest = idOf(json(send(requester, HttpMethod.POST, "/api/orders/combine-requests", CreateCombineRequestRequest(firstOrderId, targetShipmentId))))
        val secondRequest = idOf(json(send(requester, HttpMethod.POST, "/api/orders/combine-requests", CreateCombineRequestRequest(secondOrderId, targetShipmentId))))
        assertEquals(200, send(owner, HttpMethod.POST, "/api/orders/combine-requests/$firstRequest/accept").response.status)

        val rejected = send(owner, HttpMethod.POST, "/api/orders/combine-requests/$secondRequest/accept")

        assertEquals(409, rejected.response.status)
        val incoming = json(send(owner, HttpMethod.GET, "/api/orders/combine-requests/incoming"))
        assertEquals(listOf(secondRequest), incoming.map { idOf(it) })
        assertEquals(targetShipmentId.toString(), json(send(requester, HttpMethod.GET, "/api/orders/$firstOrderId")).get("shipmentId").asText())
        assertEquals(true, json(send(requester, HttpMethod.GET, "/api/orders/$secondOrderId")).get("shipmentId").isNull)
    }
}
