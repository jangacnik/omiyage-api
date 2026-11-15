package com.omiyage.backend.order

import com.ninjasquad.springmockk.MockkBean
import com.omiyage.backend.config.MeishiProperties
import com.omiyage.backend.security.BotSecurityConfig
import com.omiyage.backend.security.HmacRequestSignatureVerifier
import com.omiyage.backend.security.JwtAuthenticationFilter
import com.omiyage.backend.security.JwtVerifier
import com.omiyage.backend.security.SecurityConfig
import com.omiyage.backend.security.signRequest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.time.Instant

/** With ORDER_WEBHOOK_SECRET unset there is nothing to sign with, so the bot endpoint stays closed. */
@WebMvcTest(controllers = [BotOrderController::class])
@Import(SecurityConfig::class, JwtAuthenticationFilter::class, JwtVerifier::class, BotSecurityConfig::class, HmacRequestSignatureVerifier::class)
@EnableConfigurationProperties(MeishiProperties::class, OrderWebhookProperties::class)
@TestPropertySource(properties = ["omiyage.meishi.jwt-secret=unconfigured-jwt-secret", "omiyage.meishi.api-url=http://localhost:8080"])
class BotOrderUnconfiguredTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @MockkBean
    lateinit var service: UnshippedOrderService

    @Test
    fun `a request signed with an empty secret is still rejected`() {
        val timestamp = Instant.now().epochSecond

        mockMvc.get("/api/bot/orders") {
            header("X-Omiyage-Timestamp", timestamp.toString())
            header("X-Omiyage-Signature", signRequest("", timestamp, "GET", "/api/bot/orders", ""))
        }.andExpect { status { isUnauthorized() } }
    }
}
