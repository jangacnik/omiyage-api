package com.omiyage.backend.auth

import arrow.core.right
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
        every { service.login("bob", "hunter2", any()) } returns "the-token".right()

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
        every { service.login("carol", "pw", any()) } returns "the-token".right()
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

    @Test
    fun `public config reports the hosted login as off by default`() {
        mockMvc.get("/api/auth/config").andExpect {
            status { isOk() }
            jsonPath("$.meishiSso.enabled") { value(false) }
            jsonPath("$.meishiSso.authorizeUrl") { doesNotExist() }
            jsonPath("$.meishiSso.clientId") { doesNotExist() }
        }
    }

    @Test
    fun `exchange is 404 while the hosted login is off`() {
        mockMvc.post("/api/auth/meishi/exchange") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(MeishiExchangeRequest("code", "v".repeat(43), "https://app/cb"))
        }.andExpect { status { isNotFound() } }
        verify(exactly = 0) { service.loginWithMeishiCode(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `exchange is 404 while the hosted login is off even for an invalid body`() {
        mockMvc.post("/api/auth/meishi/exchange") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(MeishiExchangeRequest("", "short", ""))
        }.andExpect { status { isNotFound() } }
        verify(exactly = 0) { service.loginWithMeishiCode(any(), any(), any(), any(), any()) }
    }
}
