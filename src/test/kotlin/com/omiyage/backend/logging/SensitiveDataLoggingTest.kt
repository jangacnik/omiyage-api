package com.omiyage.backend.logging

import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
import com.omiyage.backend.auth.AuthController
import com.omiyage.backend.auth.AuthService
import com.omiyage.backend.auth.LoginRateLimiter
import com.omiyage.backend.auth.LoginRequest
import com.omiyage.backend.auth.MeishiAuthClient
import com.omiyage.backend.auth.MeishiUserResponse
import com.omiyage.backend.config.MeishiProperties
import com.omiyage.backend.security.JwtAuthenticationFilter
import com.omiyage.backend.security.JwtVerifier
import com.omiyage.backend.security.SecurityConfig
import io.mockk.every
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.web.server.ResponseStatusException

private const val CANARY_PASSWORD = "CANARY_PASSWORD_7f3a"
private const val CANARY_NICKNAME = "CANARY_NICKNAME_2b9d"
private const val CANARY_EMAIL = "CANARY_EMAIL_5c1e@example.com"
private const val CANARY_ADDRESS = "CANARY_ADDRESS_8d4f 1-2-3 Shibuya"
private const val CANARY_JWT = "eyJCANARY_JWT_a1b2.eyJCANARY_PAYLOAD_c3d4.CANARY_SIGNATURE_e5f6"
private const val CANARY_QUERY = "CANARY_QUERY_9a8b"
private const val CANARY_HEADER = "CANARY_HEADER_1d2c"

/** Guard for every future logging change: drives the real filter chain with distinctive canaries and fails if any reaches a log event. */
@WebMvcTest(controllers = [AuthController::class])
@Import(AuthService::class, SecurityConfig::class, JwtAuthenticationFilter::class, JwtVerifier::class, LoginRateLimiter::class)
@EnableConfigurationProperties(MeishiProperties::class)
@TestPropertySource(properties = ["omiyage.meishi.jwt-secret=canary-test-secret", "omiyage.meishi.api-url=http://localhost:8080"])
class SensitiveDataLoggingTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @MockkBean
    lateinit var meishi: MeishiAuthClient

    private fun loginBody(nickname: String, password: String) =
        objectMapper.writeValueAsString(LoginRequest(nickname, password))

    @Test
    fun `no canary value reaches any log event across login, validation, bad token and unknown route`() {
        every { meishi.login(CANARY_NICKNAME, CANARY_PASSWORD) } throws ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid credentials")

        LogCapture.root().use { capture ->
            mockMvc.post("/api/auth/login") {
                contentType = MediaType.APPLICATION_JSON
                content = loginBody(CANARY_NICKNAME, CANARY_PASSWORD)
                header("X-Forwarded-For", "203.0.113.9")
                header("X-Request-Id", "bad id $CANARY_HEADER")
            }.andExpect { }

            mockMvc.post("/api/auth/login") {
                contentType = MediaType.APPLICATION_JSON
                content = loginBody(CANARY_NICKNAME, "")
            }.andExpect { }

            mockMvc.post("/api/auth/login") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"nickname":"$CANARY_NICKNAME","password":"$CANARY_PASSWORD","email":"$CANARY_EMAIL","address":"$CANARY_ADDRESS","""
            }.andExpect { }

            mockMvc.post("/api/auth/login") {
                contentType = MediaType.APPLICATION_JSON
                content = loginBody(CANARY_NICKNAME, CANARY_PASSWORD)
                header("Authorization", "Bearer $CANARY_JWT")
                header("Cookie", "session=$CANARY_HEADER")
            }.andExpect { }

            mockMvc.get("/api/nowhere/$CANARY_ADDRESS".replace(" ", "%20") + "?email=$CANARY_EMAIL&q=$CANARY_QUERY") {
                header("Authorization", "Bearer $CANARY_JWT")
            }.andExpect { }

            val logged = capture.text()
            assertTrue(logged.contains("login_failed"), "expected the failed login to be logged at all")
            assertTrue(capture.named("access").size >= 5, "every request must produce an access line")
            CANARIES.forEach { assertFalse(logged.contains(it), "log output leaked $it:\n$logged") }
        }
    }

    @Test
    fun `every request produces exactly one access line`() {
        LogCapture("access").use { capture ->
            repeat(3) { mockMvc.get("/api/nowhere") }

            assertEquals(3, capture.events.size)
        }
    }

    @Test
    fun `rate limited logins log the client ip as a security event`() {
        every { meishi.login("carol", "pw") } returns "t"
        every { meishi.getMe("t") } returns MeishiUserResponse("u1", "carol", "USER", "ACTIVE")
        LogCapture("security").use { capture ->
            repeat(11) {
                mockMvc.post("/api/auth/login") {
                    contentType = MediaType.APPLICATION_JSON
                    content = loginBody("carol", "pw")
                    header("X-Forwarded-For", "198.51.100.4")
                }
            }

            assertEquals("login_rate_limited clientIp=198.51.100.4", capture.events.single().formattedMessage)
        }
    }

    private companion object {
        val CANARIES = listOf(
            CANARY_PASSWORD, CANARY_NICKNAME, CANARY_EMAIL, CANARY_ADDRESS, CANARY_JWT, "CANARY_JWT", "CANARY_QUERY", CANARY_HEADER,
            "Shibuya",
        )
    }
}
