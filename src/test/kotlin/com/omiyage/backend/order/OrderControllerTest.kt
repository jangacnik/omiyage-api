package com.omiyage.backend.order

import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
import com.omiyage.backend.common.Currency
import com.omiyage.backend.config.MeishiProperties
import com.omiyage.backend.security.JwtAuthenticationFilter
import com.omiyage.backend.security.JwtVerifier
import com.omiyage.backend.security.SecurityConfig
import com.omiyage.backend.security.TestJwt
import com.omiyage.backend.shipment.ShipmentService
import com.omiyage.backend.web.PageResponse
import io.mockk.every
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.OffsetDateTime
import java.util.UUID

private const val TEST_SECRET = "order-controller-secret"

@WebMvcTest(controllers = [OrderController::class])
@Import(SecurityConfig::class, JwtAuthenticationFilter::class, JwtVerifier::class)
@EnableConfigurationProperties(MeishiProperties::class)
@TestPropertySource(properties = ["omiyage.meishi.jwt-secret=$TEST_SECRET", "omiyage.meishi.api-url=http://localhost:8080"])
class OrderControllerTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @MockkBean
    lateinit var service: OrderService

    @MockkBean
    lateinit var shipments: ShipmentService

    private val userId = UUID.randomUUID()
    private fun token() = TestJwt.sign(TEST_SECRET, subject = userId.toString(), role = "USER")

    private fun sampleOrder() = OrderDto(
        id = UUID.randomUUID(),
        userId = userId,
        status = OrderStatus.REQUESTED,
        open = true,
        notes = null,
        nickname = null,
        shipmentId = null,
        createdAt = OffsetDateTime.now(),
        updatedAt = OffsetDateTime.now(),
        items = emptyList(),
    )

    @Test
    fun `create requires authentication`() {
        val request = CreateOrderRequest(items = listOf(CreateOrderItemRequest(title = "Kit Kat", currency = Currency.JPY)))
        mockMvc.post("/api/orders") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(request)
        }.andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `create rejects an empty items list`() {
        val request = CreateOrderRequest(items = emptyList())
        mockMvc.post("/api/orders") {
            header("Authorization", "Bearer ${token()}")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(request)
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `create returns the created order`() {
        val request = CreateOrderRequest(items = listOf(CreateOrderItemRequest(title = "Kit Kat", currency = Currency.JPY)))
        every { service.create(any(), request) } returns sampleOrder()

        mockMvc.post("/api/orders") {
            header("Authorization", "Bearer ${token()}")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(request)
        }.andExpect {
            status { isCreated() }
            jsonPath("$.status") { value("REQUESTED") }
        }
    }

    @Test
    fun `listMine returns the caller's orders`() {
        every { service.listMine(any(), any()) } returns PageResponse(listOf(sampleOrder()), 0, 20, 1, 1)

        mockMvc.get("/api/orders/mine") { header("Authorization", "Bearer ${token()}") }
            .andExpect {
                status { isOk() }
                jsonPath("$.totalElements") { value(1) }
            }
    }

    @Test
    fun `getById returns the order`() {
        val order = sampleOrder()
        every { service.getById(order.id, any()) } returns order

        mockMvc.get("/api/orders/${order.id}") { header("Authorization", "Bearer ${token()}") }
            .andExpect {
                status { isOk() }
                jsonPath("$.id") { value(order.id.toString()) }
            }
    }
}
