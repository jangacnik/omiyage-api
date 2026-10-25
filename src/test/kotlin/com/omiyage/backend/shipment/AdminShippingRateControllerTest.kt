package com.omiyage.backend.shipment

import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
import com.omiyage.backend.config.MeishiProperties
import com.omiyage.backend.security.JwtAuthenticationFilter
import com.omiyage.backend.security.JwtVerifier
import com.omiyage.backend.security.SecurityConfig
import com.omiyage.backend.security.TestJwt
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
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
import java.util.UUID

private const val TEST_SECRET = "admin-shipping-rate-controller-secret"

@WebMvcTest(controllers = [AdminShippingRateController::class])
@Import(SecurityConfig::class, JwtAuthenticationFilter::class, JwtVerifier::class)
@EnableConfigurationProperties(MeishiProperties::class)
@TestPropertySource(properties = ["omiyage.meishi.jwt-secret=$TEST_SECRET", "omiyage.meishi.api-url=http://localhost:8080"])
class AdminShippingRateControllerTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @MockkBean
    lateinit var service: ShippingRateService

    private fun userToken() = TestJwt.sign(TEST_SECRET, role = "USER")
    private fun adminToken() = TestJwt.sign(TEST_SECRET, role = "ADMIN")

    private fun sampleRequest() = ShippingRateRequest(
        destination = ShipmentDestination.AUSTRIA,
        shippingMethod = ShippingMethod.EMS,
        maxWeightGrams = 1000,
        costJpy = 4400,
        estimatedDays = 7,
    )

    @Test
    fun `list is rejected for a non-admin user`() {
        mockMvc.get("/api/admin/shipping-rates") { header("Authorization", "Bearer ${userToken()}") }
            .andExpect { status { isForbidden() } }
    }

    @Test
    fun `create succeeds for an admin`() {
        val request = sampleRequest()
        every { service.create(request) } returns ShippingRateDto(
            id = UUID.randomUUID(),
            destination = request.destination,
            shippingMethod = request.shippingMethod,
            maxWeightGrams = request.maxWeightGrams,
            costJpy = request.costJpy,
            costEur = java.math.BigDecimal("30.25"),
            estimatedDays = request.estimatedDays,
        )

        mockMvc.post("/api/admin/shipping-rates") {
            header("Authorization", "Bearer ${adminToken()}")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(request)
        }.andExpect { status { isCreated() } }
    }

    @Test
    fun `delete succeeds for an admin`() {
        val id = UUID.randomUUID()
        every { service.delete(id) } just Runs

        mockMvc.delete("/api/admin/shipping-rates/$id") { header("Authorization", "Bearer ${adminToken()}") }
            .andExpect { status { isNoContent() } }
    }
}
