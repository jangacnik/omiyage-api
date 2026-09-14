package com.omiyage.backend.shipment

import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
import com.omiyage.backend.config.MeishiProperties
import com.omiyage.backend.security.JwtAuthenticationFilter
import com.omiyage.backend.security.JwtVerifier
import com.omiyage.backend.security.SecurityConfig
import com.omiyage.backend.security.TestJwt
import io.mockk.every
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.put
import java.math.BigDecimal

private const val TEST_SECRET = "exchange-rate-controllers-secret"

@WebMvcTest(controllers = [AdminExchangeRateController::class, ExchangeRateController::class])
@Import(SecurityConfig::class, JwtAuthenticationFilter::class, JwtVerifier::class)
@EnableConfigurationProperties(MeishiProperties::class)
@TestPropertySource(properties = ["omiyage.meishi.jwt-secret=$TEST_SECRET", "omiyage.meishi.api-url=http://localhost:8080"])
class ExchangeRateControllersTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @MockkBean
    lateinit var service: ExchangeRateService

    private fun userToken() = TestJwt.sign(TEST_SECRET, role = "USER")
    private fun adminToken() = TestJwt.sign(TEST_SECRET, role = "ADMIN")

    @Test
    fun `admin rate endpoint is rejected for a non-admin user`() {
        mockMvc.get("/api/admin/exchange-rate") { header("Authorization", "Bearer ${userToken()}") }
            .andExpect { status { isForbidden() } }
    }

    @Test
    fun `admin can read and update the rate, response never carries the markup`() {
        every { service.getRate() } returns BigDecimal("150.0000")

        mockMvc.get("/api/admin/exchange-rate") { header("Authorization", "Bearer ${adminToken()}") }
            .andExpect {
                status { isOk() }
                content { json("""{"jpyPerEur":150.0000}""") }
            }

        every { service.setRate(BigDecimal("155.0000")) } returns Unit
        every { service.getRate() } returns BigDecimal("155.0000")
        mockMvc.put("/api/admin/exchange-rate") {
            header("Authorization", "Bearer ${adminToken()}")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(SetExchangeRateRequest(BigDecimal("155.0000")))
        }.andExpect { status { isOk() } }
    }

    @Test
    fun `shipping rate endpoint is rejected for a non-admin user`() {
        mockMvc.get("/api/admin/exchange-rate/shipping") { header("Authorization", "Bearer ${userToken()}") }
            .andExpect { status { isForbidden() } }
    }

    @Test
    fun `admin can read and update the shipping rate independently of the item rate`() {
        every { service.getShippingRate() } returns BigDecimal("100.0000")

        mockMvc.get("/api/admin/exchange-rate/shipping") { header("Authorization", "Bearer ${adminToken()}") }
            .andExpect {
                status { isOk() }
                content { json("""{"shippingJpyPerEur":100.0000}""") }
            }

        every { service.setShippingRate(BigDecimal("105.0000")) } returns Unit
        every { service.getShippingRate() } returns BigDecimal("105.0000")
        mockMvc.put("/api/admin/exchange-rate/shipping") {
            header("Authorization", "Bearer ${adminToken()}")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(SetShippingExchangeRateRequest(BigDecimal("105.0000")))
        }.andExpect { status { isOk() } }
    }

    @Test
    fun `the plain rate endpoint is reachable by any authenticated user, not just admins`() {
        every { service.getRate() } returns BigDecimal("150.0000")

        mockMvc.get("/api/exchange-rate") { header("Authorization", "Bearer ${userToken()}") }
            .andExpect {
                status { isOk() }
                content { json("""{"jpyPerEur":150.0000}""") }
            }
    }
}
