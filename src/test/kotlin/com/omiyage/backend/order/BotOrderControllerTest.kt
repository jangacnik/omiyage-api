package com.omiyage.backend.order

import com.ninjasquad.springmockk.MockkBean
import com.omiyage.backend.config.MeishiProperties
import com.omiyage.backend.security.BotSecurityConfig
import com.omiyage.backend.security.HmacRequestSignatureVerifier
import com.omiyage.backend.security.JwtAuthenticationFilter
import com.omiyage.backend.security.JwtVerifier
import com.omiyage.backend.security.SecurityConfig
import com.omiyage.backend.security.TestJwt
import com.omiyage.backend.security.signRequest
import com.omiyage.backend.web.PageResponse
import io.mockk.every
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

private const val JWT_SECRET = "bot-order-controller-jwt-secret"
private const val SIGNING_SECRET = "bot-order-controller-signing-secret"
private const val PATH = "/api/bot/orders"
private const val QUERY = "page=0&size=25"

@WebMvcTest(controllers = [BotOrderController::class])
@Import(SecurityConfig::class, JwtAuthenticationFilter::class, JwtVerifier::class, BotSecurityConfig::class, HmacRequestSignatureVerifier::class)
@EnableConfigurationProperties(MeishiProperties::class, OrderWebhookProperties::class)
@TestPropertySource(
    properties = [
        "omiyage.meishi.jwt-secret=$JWT_SECRET",
        "omiyage.meishi.api-url=http://localhost:8080",
        "omiyage.order-webhook.secret=$SIGNING_SECRET",
    ],
)
class BotOrderControllerTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @MockkBean
    lateinit var service: UnshippedOrderService

    private val orderId = UUID.fromString("1a2b3c4d-0000-0000-0000-000000000000")

    private fun now() = Instant.now().epochSecond

    private fun signedGet(
        timestamp: Long = now(),
        secret: String = SIGNING_SECRET,
        query: String = QUERY,
        signedQuery: String = query,
    ) = mockMvc.get("$PATH?$query") {
        header("X-Omiyage-Timestamp", timestamp.toString())
        header("X-Omiyage-Signature", signRequest(secret, timestamp, "GET", PATH, signedQuery))
    }

    @Test
    fun `lists the unshipped orders for a correctly signed request`() {
        every { service.list(0, 25) } returns PageResponse(
            content = listOf(BotOrderDto(orderId, "1a2b3c4d", OrderStatus.PURCHASED, "Birthday presents", 3, OffsetDateTime.parse("2026-11-01T10:00:00Z"))),
            page = 0,
            size = 25,
            totalElements = 1,
            totalPages = 1,
            hasNext = false,
        )

        signedGet().andExpect {
            status { isOk() }
            jsonPath("$.content[0].id") { value(orderId.toString()) }
            jsonPath("$.content[0].reference") { value("1a2b3c4d") }
            jsonPath("$.content[0].status") { value("PURCHASED") }
            jsonPath("$.content[0].nickname") { value("Birthday presents") }
            jsonPath("$.content[0].itemCount") { value(3) }
            jsonPath("$.totalElements") { value(1) }
        }
    }

    @Test
    fun `passes the page and size query parameters on`() {
        every { service.list(2, 10) } returns PageResponse(emptyList(), 2, 10, 0, 0, false)

        signedGet(query = "page=2&size=10").andExpect { status { isOk() } }

        verify { service.list(2, 10) }
    }

    @Test
    fun `rejects a request with no signature`() {
        mockMvc.get("$PATH?$QUERY").andExpect {
            status { isUnauthorized() }
            jsonPath("$.code") { value("unauthorized") }
        }

        verify(exactly = 0) { service.list(any(), any()) }
    }

    @Test
    fun `rejects a bad signature`() {
        signedGet(secret = "other-secret").andExpect { status { isUnauthorized() } }

        verify(exactly = 0) { service.list(any(), any()) }
    }

    @Test
    fun `rejects a signature that was made for another query`() {
        signedGet(query = "page=0&size=50", signedQuery = QUERY).andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `rejects a stale timestamp`() {
        signedGet(timestamp = now() - 11 * 60).andExpect { status { isUnauthorized() } }

        verify(exactly = 0) { service.list(any(), any()) }
    }

    @Test
    fun `rejects a timestamp from the future`() {
        signedGet(timestamp = now() + 2 * 60).andExpect { status { isUnauthorized() } }

        verify(exactly = 0) { service.list(any(), any()) }
    }

    @Test
    fun `an admin token without a signature is not enough`() {
        mockMvc.get("$PATH?$QUERY") { header("Authorization", "Bearer ${TestJwt.sign(JWT_SECRET, role = "ADMIN")}") }
            .andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `a valid signature does not open the admin api`() {
        mockMvc.get("/api/admin/ping") {
            header("X-Omiyage-Timestamp", now().toString())
            header("X-Omiyage-Signature", signRequest(SIGNING_SECRET, now(), "GET", "/api/admin/ping", ""))
        }.andExpect { status { isUnauthorized() } }
    }
}
