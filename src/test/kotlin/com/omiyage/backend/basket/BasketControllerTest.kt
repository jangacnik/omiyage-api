package com.omiyage.backend.basket

import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
import com.omiyage.backend.common.Currency
import com.omiyage.backend.config.MeishiProperties
import com.omiyage.backend.order.OrderDto
import com.omiyage.backend.order.OrderStatus
import com.omiyage.backend.security.JwtAuthenticationFilter
import com.omiyage.backend.security.JwtVerifier
import com.omiyage.backend.security.SecurityConfig
import com.omiyage.backend.security.TestJwt
import io.mockk.every
import io.mockk.just
import io.mockk.Runs
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import java.time.OffsetDateTime
import java.util.UUID

private const val TEST_SECRET = "basket-controller-secret"

@WebMvcTest(controllers = [BasketController::class])
@Import(SecurityConfig::class, JwtAuthenticationFilter::class, JwtVerifier::class)
@EnableConfigurationProperties(MeishiProperties::class)
@TestPropertySource(properties = ["omiyage.meishi.jwt-secret=$TEST_SECRET", "omiyage.meishi.api-url=http://localhost:8080"])
class BasketControllerTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @MockkBean
    lateinit var service: BasketService

    private val userId = UUID.randomUUID()
    private fun token() = TestJwt.sign(TEST_SECRET, subject = userId.toString(), role = "USER")

    private fun sampleLine(quantity: Int = 1) = BasketLineDto(
        id = UUID.randomUUID(),
        inventoryItemId = null,
        customTitle = "Kit Kat",
        customDescription = null,
        currency = Currency.JPY,
        maxPrice = null,
        quantity = quantity,
        createdAt = OffsetDateTime.now(),
    )

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
    fun `list requires authentication`() {
        mockMvc.get("/api/basket").andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `list returns the caller's lines`() {
        every { service.list(match { it.id == userId }) } returns listOf(sampleLine(quantity = 3))

        mockMvc.get("/api/basket") { header("Authorization", "Bearer ${token()}") }
            .andExpect {
                status { isOk() }
                jsonPath("$[0].quantity") { value(3) }
                jsonPath("$[0].customTitle") { value("Kit Kat") }
                jsonPath("$[0].currency") { value("JPY") }
            }
    }

    @Test
    fun `add returns 201 with the created line`() {
        val request = AddBasketLineRequest(customTitle = "Kit Kat", currency = Currency.JPY, quantity = 2)
        every { service.add(match { it.id == userId }, request) } returns sampleLine(quantity = 2)

        mockMvc.post("/api/basket/lines") {
            header("Authorization", "Bearer ${token()}")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(request)
        }.andExpect {
            status { isCreated() }
            jsonPath("$.quantity") { value(2) }
            jsonPath("$.customTitle") { value("Kit Kat") }
        }
    }

    @Test
    fun `add rejects a zero quantity`() {
        val request = AddBasketLineRequest(customTitle = "Kit Kat", currency = Currency.JPY, quantity = 0)

        mockMvc.post("/api/basket/lines") {
            header("Authorization", "Bearer ${token()}")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(request)
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `update returns the updated line`() {
        val lineId = UUID.randomUUID()
        val request = UpdateBasketLineRequest(quantity = 5)
        every { service.update(match { it.id == userId }, lineId, request) } returns sampleLine(quantity = 5)

        mockMvc.put("/api/basket/lines/$lineId") {
            header("Authorization", "Bearer ${token()}")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(request)
        }.andExpect {
            status { isOk() }
            jsonPath("$.quantity") { value(5) }
        }
    }

    @Test
    fun `remove returns 204 and delegates to the service`() {
        val lineId = UUID.randomUUID()
        every { service.remove(any(), lineId) } just Runs

        mockMvc.delete("/api/basket/lines/$lineId") { header("Authorization", "Bearer ${token()}") }
            .andExpect { status { isNoContent() } }

        verify(exactly = 1) { service.remove(match { it.id == userId }, lineId) }
    }

    @Test
    fun `clear returns 204 and delegates to the service`() {
        every { service.clear(any()) } just Runs

        mockMvc.delete("/api/basket") { header("Authorization", "Bearer ${token()}") }
            .andExpect { status { isNoContent() } }

        verify(exactly = 1) { service.clear(match { it.id == userId }) }
    }

    @Test
    fun `confirm returns the created order`() {
        every { service.confirm(match { it.id == userId }) } returns sampleOrder()

        mockMvc.post("/api/basket/confirm") { header("Authorization", "Bearer ${token()}") }
            .andExpect {
                status { isOk() }
                jsonPath("$.status") { value("REQUESTED") }
                jsonPath("$.open") { value(true) }
                jsonPath("$.userId") { value(userId.toString()) }
            }
    }

    @Test
    fun `addToOrder passes the order id to the service`() {
        val orderId = UUID.randomUUID()
        every { service.addToOrder(match { it.id == userId }, orderId) } returns sampleOrder()

        mockMvc.post("/api/basket/add-to-order") {
            header("Authorization", "Bearer ${token()}")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(AddBasketToOrderRequest(orderId))
        }.andExpect { status { isOk() } }

        verify(exactly = 1) { service.addToOrder(any(), orderId) }
    }

    @Test
    fun `addToOrder rejects a missing order id`() {
        mockMvc.post("/api/basket/add-to-order") {
            header("Authorization", "Bearer ${token()}")
            contentType = MediaType.APPLICATION_JSON
            content = "{}"
        }.andExpect { status { isBadRequest() } }
    }
}
