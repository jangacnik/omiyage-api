package com.omiyage.backend.auth

import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
import com.omiyage.backend.config.MeishiProperties
import com.omiyage.backend.security.JwtAuthenticationFilter
import com.omiyage.backend.security.JwtVerifier
import com.omiyage.backend.security.SecurityConfig
import io.mockk.every
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post

@WebMvcTest(controllers = [AuthController::class])
@Import(SecurityConfig::class, JwtAuthenticationFilter::class, JwtVerifier::class, LoginRateLimiter::class)
@EnableConfigurationProperties(MeishiProperties::class)
@TestPropertySource(properties = ["omiyage.meishi.jwt-secret=auth-controller-secret", "omiyage.meishi.api-url=http://localhost:8080"])
class AuthControllerTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @MockkBean
    lateinit var service: AuthService

    @Test
    fun `login is public and does not require a token`() {
        every { service.login("bob", "hunter2") } returns "the-token"

        mockMvc.post("/api/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(LoginRequest("bob", "hunter2"))
        }.andExpect {
            status { isOk() }
            jsonPath("$.token") { value("the-token") }
        }
    }

    @Test
    fun `login rejects a blank password`() {
        mockMvc.post("/api/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(LoginRequest("bob", ""))
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `login is throttled after too many attempts for one nickname`() {
        every { service.login("carol", "pw") } returns "the-token"
        val body = objectMapper.writeValueAsString(LoginRequest("carol", "pw"))

        repeat(10) {
            mockMvc.post("/api/auth/login") {
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andExpect { status { isOk() } }
        }

        mockMvc.post("/api/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = body
        }.andExpect { status { isTooManyRequests() } }
    }
}
