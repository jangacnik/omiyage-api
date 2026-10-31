package com.omiyage.backend.combine

import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
import com.omiyage.backend.config.MeishiProperties
import com.omiyage.backend.security.JwtAuthenticationFilter
import com.omiyage.backend.security.JwtVerifier
import com.omiyage.backend.security.SecurityConfig
import com.omiyage.backend.security.TestJwt
import com.omiyage.backend.shipment.ShipmentSummaryDto
import io.mockk.every
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
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

private const val TEST_SECRET = "combine-controller-secret"

@WebMvcTest(controllers = [CombineRequestController::class, AdminCombineRequestController::class])
@Import(SecurityConfig::class, JwtAuthenticationFilter::class, JwtVerifier::class)
@EnableConfigurationProperties(MeishiProperties::class)
@TestPropertySource(properties = ["omiyage.meishi.jwt-secret=$TEST_SECRET", "omiyage.meishi.api-url=http://localhost:8080"])
class CombineRequestControllerTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @MockkBean
    lateinit var service: CombineRequestService

    private val userId = UUID.randomUUID()
    private fun userToken() = TestJwt.sign(TEST_SECRET, subject = userId.toString(), role = "USER")
    private fun adminToken() = TestJwt.sign(TEST_SECRET, role = "ADMIN")

    private fun sampleDto(status: CombineRequestStatus = CombineRequestStatus.PENDING) = CombineRequestDto(
        id = UUID.randomUUID(),
        requesterOrder = OrderSummaryDto(UUID.randomUUID(), "bob", 2),
        targetShipment = ShipmentSummaryDto(UUID.randomUUID(), "Box", 3, BigDecimal("500")),
        status = status,
        createdAt = OffsetDateTime.now(),
        respondedAt = null,
        resultingShipmentId = null,
    )

    @Test
    fun `create requires authentication`() {
        mockMvc.post("/api/orders/combine-requests") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(CreateCombineRequestRequest(UUID.randomUUID(), UUID.randomUUID()))
        }.andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `create returns 201 with the new request`() {
        val request = CreateCombineRequestRequest(UUID.randomUUID(), UUID.randomUUID())
        every {
            service.create(match { it.id == userId }, request.requesterOrderId, request.targetShipmentId)
        } returns sampleDto()

        mockMvc.post("/api/orders/combine-requests") {
            header("Authorization", "Bearer ${userToken()}")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(request)
        }.andExpect {
            status { isCreated() }
            jsonPath("$.status") { value("PENDING") }
            jsonPath("$.requesterOrder.itemCount") { value(2) }
        }
    }

    @Test
    fun `create rejects a body without ids`() {
        mockMvc.post("/api/orders/combine-requests") {
            header("Authorization", "Bearer ${userToken()}")
            contentType = MediaType.APPLICATION_JSON
            content = "{}"
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `incoming and outgoing list the caller's requests`() {
        every { service.listIncoming(match { it.id == userId }) } returns listOf(sampleDto())
        every { service.listOutgoing(match { it.id == userId }) } returns listOf(sampleDto(), sampleDto())

        mockMvc.get("/api/orders/combine-requests/incoming") { header("Authorization", "Bearer ${userToken()}") }
            .andExpect { status { isOk() }; jsonPath("$.length()") { value(1) } }
        mockMvc.get("/api/orders/combine-requests/outgoing") { header("Authorization", "Bearer ${userToken()}") }
            .andExpect { status { isOk() }; jsonPath("$.length()") { value(2) } }
    }

    @Test
    fun `accept, decline and cancel return the updated request`() {
        val id = UUID.randomUUID()
        every { service.accept(any(), id) } returns sampleDto(CombineRequestStatus.ACCEPTED)
        every { service.decline(any(), id) } returns sampleDto(CombineRequestStatus.DECLINED)
        every { service.cancel(any(), id) } returns sampleDto(CombineRequestStatus.CANCELLED)

        mockMvc.post("/api/orders/combine-requests/$id/accept") { header("Authorization", "Bearer ${userToken()}") }
            .andExpect { status { isOk() }; jsonPath("$.status") { value("ACCEPTED") } }
        mockMvc.post("/api/orders/combine-requests/$id/decline") { header("Authorization", "Bearer ${userToken()}") }
            .andExpect { status { isOk() }; jsonPath("$.status") { value("DECLINED") } }
        mockMvc.delete("/api/orders/combine-requests/$id") { header("Authorization", "Bearer ${userToken()}") }
            .andExpect { status { isOk() }; jsonPath("$.status") { value("CANCELLED") } }
    }

    @Test
    fun `admin list is forbidden for a non-admin user`() {
        mockMvc.get("/api/admin/combine-requests") { header("Authorization", "Bearer ${userToken()}") }
            .andExpect { status { isForbidden() } }
    }

    @Test
    fun `admin lists pending requests`() {
        every { service.listAllPending() } returns listOf(sampleDto())

        mockMvc.get("/api/admin/combine-requests") { header("Authorization", "Bearer ${adminToken()}") }
            .andExpect { status { isOk() }; jsonPath("$.length()") { value(1) } }
    }

    @Test
    fun `admin force-accepts a request`() {
        val id = UUID.randomUUID()
        every { service.forceAccept(id) } returns sampleDto(CombineRequestStatus.ACCEPTED)

        mockMvc.post("/api/admin/combine-requests/$id/force-accept") { header("Authorization", "Bearer ${adminToken()}") }
            .andExpect { status { isOk() }; jsonPath("$.status") { value("ACCEPTED") } }
    }
}
