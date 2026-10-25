package com.omiyage.backend

import com.fasterxml.jackson.databind.ObjectMapper
import com.omiyage.backend.catalog.InventoryItemRequest
import com.omiyage.backend.common.Condition
import com.omiyage.backend.common.Currency
import com.omiyage.backend.order.CreateOrderItemRequest
import com.omiyage.backend.order.CreateOrderRequest
import com.omiyage.backend.order.OrderItemStatus
import com.omiyage.backend.order.UpdateOrderItemRequest
import com.omiyage.backend.order.UpdateOrderStatusRequest
import com.omiyage.backend.order.OrderStatus
import com.omiyage.backend.security.TestJwt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.transaction.annotation.Transactional
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.math.BigDecimal
import java.util.UUID

private const val TEST_SECRET = "full-flow-secret"

/** Drives the app end to end over real HTTP-level MockMvc requests, the real security filter
 *  chain, and real Postgres — the shape of thing a unit or web-slice test can't catch:
 *  wrong @RequestMapping paths, JSON (de)serialization drift, or a security rule that's wired to
 *  the wrong path pattern. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class FullFlowIntegrationTest : PostgresIntegrationTest() {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("omiyage.meishi.jwt-secret") { TEST_SECRET }
        }
    }

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    private fun userToken() = TestJwt.sign(TEST_SECRET, subject = UUID.randomUUID().toString(), nickname = "bob", role = "USER")
    private fun adminToken() = TestJwt.sign(TEST_SECRET, subject = UUID.randomUUID().toString(), nickname = "root", role = "ADMIN")

    @Test
    fun `create order with an image, admin marks it bought, user sees the updated status`() {
        val admin = adminToken()
        val user = userToken()

        // Admin uploads a photo and creates an inventory item with it.
        val image = MockMultipartFile("file", "photo.jpg", "image/jpeg", byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()))
        val uploadResult = mockMvc.perform(multipart("/api/images").file(image).header("Authorization", "Bearer $admin"))
            .andExpect(status().isCreated)
            .andReturn()
        val imageKey = objectMapper.readTree(uploadResult.response.contentAsString).get("key").asText()

        val itemRequest = InventoryItemRequest(
            title = "Kit Kat matcha 12-pack",
            description = "Sealed box",
            condition = Condition.NEW,
            priceAmount = BigDecimal("1200.00"),
            currency = Currency.JPY,
            stockQuantity = 3,
            imageKeys = listOf(imageKey),
        )
        val createItemResult = mockMvc.perform(
            post("/api/admin/inventory")
                .header("Authorization", "Bearer $admin")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(itemRequest)),
        ).andExpect(status().isCreated).andReturn()
        val itemId = objectMapper.readTree(createItemResult.response.contentAsString).get("id").asText()

        // User finds it via browse/search and orders it, alongside a custom request item.
        mockMvc.perform(get("/api/inventory").param("title", "kit kat").header("Authorization", "Bearer $user"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content[0].id").value(itemId))

        val orderRequest = CreateOrderRequest(
            items = listOf(
                CreateOrderItemRequest(inventoryItemId = UUID.fromString(itemId), currency = Currency.JPY),
                CreateOrderItemRequest(title = "Ghibli museum ticket", condition = Condition.NEW, currency = Currency.JPY, maxPrice = BigDecimal("2000")),
            ),
        )
        val createOrderResult = mockMvc.perform(
            post("/api/orders")
                .header("Authorization", "Bearer $user")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(orderRequest)),
        ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.status").value("REQUESTED"))
            .andExpect(jsonPath("$.items.length()").value(2))
            .andReturn()
        val orderJson = objectMapper.readTree(createOrderResult.response.contentAsString)
        val orderId = orderJson.get("id").asText()
        val firstItemId = orderJson.get("items").get(0).get("id").asText()

        // Admin sees it in the queue, marks the first item bought, and advances the order.
        mockMvc.perform(get("/api/admin/orders").header("Authorization", "Bearer $admin"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totalElements").value(1))

        mockMvc.perform(
            patch("/api/admin/orders/$orderId/items/$firstItemId")
                .header("Authorization", "Bearer $admin")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(UpdateOrderItemRequest(status = OrderItemStatus.BOUGHT, actualPrice = BigDecimal("1150.00")))),
        ).andExpect(status().isOk)
            .andExpect(jsonPath("$.items[?(@.id=='$firstItemId')].status").value("BOUGHT"))

        mockMvc.perform(
            patch("/api/admin/orders/$orderId")
                .header("Authorization", "Bearer $admin")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(UpdateOrderStatusRequest(OrderStatus.IN_PROGRESS))),
        ).andExpect(status().isOk).andExpect(jsonPath("$.status").value("IN_PROGRESS"))

        // User sees the update, and the still-untouched second item keeps its original status.
        val finalOrder = mockMvc.perform(get("/api/orders/$orderId").header("Authorization", "Bearer $user"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
            .andReturn()
        val items = objectMapper.readTree(finalOrder.response.contentAsString).get("items")
        val statuses = items.map { it.get("status").asText() }
        assertEquals(1, statuses.count { it == "BOUGHT" })
        assertTrue(statuses.contains("REQUESTED"))
    }
}
