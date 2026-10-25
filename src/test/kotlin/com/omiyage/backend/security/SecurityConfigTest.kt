package com.omiyage.backend.security

import com.omiyage.backend.config.MeishiProperties
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

private const val TEST_SECRET = "webmvctest-secret"

@RestController
class PingTestController {
    @GetMapping("/api/ping")
    fun ping() = mapOf("pong" to true)

    @GetMapping("/api/admin/ping")
    fun adminPing() = mapOf("pong" to true)
}

@WebMvcTest(controllers = [PingTestController::class])
@Import(SecurityConfig::class, JwtAuthenticationFilter::class, JwtVerifier::class)
@EnableConfigurationProperties(MeishiProperties::class)
@TestPropertySource(properties = ["omiyage.meishi.jwt-secret=$TEST_SECRET", "omiyage.meishi.api-url=http://localhost:8080"])
class SecurityConfigTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    private fun token(role: String, expiresInMillis: Long = 60_000): String =
        TestJwt.sign(TEST_SECRET, role = role, expiresAtEpochSeconds = System.currentTimeMillis() / 1000 + expiresInMillis / 1000)

    @Test
    fun `rejects a request with no token`() {
        mockMvc.get("/api/ping").andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `allows an authenticated user on a plain endpoint`() {
        mockMvc.get("/api/ping") { header("Authorization", "Bearer ${token("USER")}") }
            .andExpect { status { isOk() } }
    }

    @Test
    fun `rejects a non-admin user on an admin endpoint`() {
        mockMvc.get("/api/admin/ping") { header("Authorization", "Bearer ${token("USER")}") }
            .andExpect { status { isForbidden() } }
    }

    @Test
    fun `allows an admin on an admin endpoint`() {
        mockMvc.get("/api/admin/ping") { header("Authorization", "Bearer ${token("ADMIN")}") }
            .andExpect { status { isOk() } }
    }

    @Test
    fun `rejects an expired token`() {
        mockMvc.get("/api/ping") { header("Authorization", "Bearer ${token("USER", expiresInMillis = -1000)}") }
            .andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `rejects a malformed authorization header`() {
        mockMvc.get("/api/ping") { header("Authorization", "not-a-bearer-token") }
            .andExpect { status { isUnauthorized() } }
    }
}
