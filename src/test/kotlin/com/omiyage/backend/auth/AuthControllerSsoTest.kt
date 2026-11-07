package com.omiyage.backend.auth

import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
import com.omiyage.backend.config.MeishiProperties
import com.omiyage.backend.security.JwtAuthenticationFilter
import com.omiyage.backend.security.JwtVerifier
import com.omiyage.backend.security.SecurityConfig
import io.mockk.every
import io.mockk.verify
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
import org.springframework.web.server.ResponseStatusException

private const val VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
private const val REDIRECT = "https://omiyage.example/auth/callback"

@WebMvcTest(controllers = [AuthController::class])
@Import(SecurityConfig::class, JwtAuthenticationFilter::class, JwtVerifier::class, LoginRateLimiter::class)
@EnableConfigurationProperties(MeishiProperties::class)
@TestPropertySource(
    properties = [
        "omiyage.meishi.jwt-secret=auth-controller-sso-secret",
        "omiyage.meishi.api-url=http://localhost:8080",
        "omiyage.meishi.sso-enabled=true",
        "omiyage.meishi.sso-client-id=omiyage",
        "omiyage.meishi.sso-authorize-url=https://meishi.example/oauth/authorize",
    ],
)
class AuthControllerSsoTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @MockkBean
    lateinit var service: AuthService

    private fun exchange(
        request: MeishiExchangeRequest = MeishiExchangeRequest("the-code", VERIFIER, REDIRECT),
        clientIp: String = "198.51.100.1",
    ) =
        mockMvc.post("/api/auth/meishi/exchange") {
            contentType = MediaType.APPLICATION_JSON
            header("X-Forwarded-For", clientIp)
            content = objectMapper.writeValueAsString(request)
        }

    @Test
    fun `public config advertises the hosted login without a token`() {
        mockMvc.get("/api/auth/config").andExpect {
            status { isOk() }
            jsonPath("$.meishiSso.enabled") { value(true) }
            jsonPath("$.meishiSso.authorizeUrl") { value("https://meishi.example/oauth/authorize") }
            jsonPath("$.meishiSso.clientId") { value("omiyage") }
        }
    }

    @Test
    fun `exchange is public and returns the token in the login shape`() {
        every { service.loginWithMeishiCode("omiyage", "the-code", VERIFIER, REDIRECT, any()) } returns "the-token"

        exchange().andExpect {
            status { isOk() }
            jsonPath("$.token") { value("the-token") }
        }
    }

    @Test
    fun `exchange passes on a rejection from the service`() {
        every { service.loginWithMeishiCode(any(), any(), any(), any(), any()) } throws
            ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN, "account is frozen")

        exchange().andExpect { status { isForbidden() } }
    }

    @Test
    fun `exchange rejects a missing code and a verifier that is too short`() {
        exchange(MeishiExchangeRequest("", VERIFIER, REDIRECT)).andExpect { status { isBadRequest() } }
        exchange(MeishiExchangeRequest("the-code", "short", REDIRECT)).andExpect { status { isBadRequest() } }
        exchange(MeishiExchangeRequest("the-code", "v".repeat(42) + "!", REDIRECT)).andExpect { status { isBadRequest() } }
        exchange(MeishiExchangeRequest("the-code", VERIFIER, "")).andExpect { status { isBadRequest() } }
        verify(exactly = 0) { service.loginWithMeishiCode(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `exchange shares the login throttle per client ip`() {
        every { service.loginWithMeishiCode(any(), any(), any(), any(), any()) } returns "the-token"

        repeat(20) { exchange(clientIp = "203.0.113.9").andExpect { status { isOk() } } }

        exchange(clientIp = "203.0.113.9").andExpect { status { isTooManyRequests() } }
        exchange(clientIp = "203.0.113.10").andExpect { status { isOk() } }
    }
}
