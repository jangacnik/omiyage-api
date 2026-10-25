package com.omiyage.backend.security

import com.fasterxml.jackson.databind.ObjectMapper
import com.omiyage.backend.config.MeishiProperties
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class JwtAuthenticationFilterTest {

    private val secret = "filter-test-secret"
    private val checker = mockk<AccountStatusChecker>()
    private val filter = JwtAuthenticationFilter(
        JwtVerifier(MeishiProperties(jwtSecret = secret, apiUrl = "http://localhost:8080"), ObjectMapper()),
        checker,
        ObjectMapper(),
    )

    private fun call(method: String, path: String): MockHttpServletResponse {
        val request = MockHttpServletRequest(method, path).apply {
            addHeader("Authorization", "Bearer ${TestJwt.sign(secret)}")
        }
        val response = MockHttpServletResponse()
        filter.doFilter(request, response, MockFilterChain())
        return response
    }

    @Test
    fun `a mutating call by a disallowed account gets 403`() {
        every { checker.isAllowed(any(), any()) } returns false
        assertEquals(403, call("POST", "/api/orders").status)
    }

    @Test
    fun `a plain read does not consult the account status`() {
        every { checker.isAllowed(any(), any()) } returns false
        assertEquals(200, call("GET", "/api/orders").status)
    }

    @Test
    fun `an admin read is checked`() {
        every { checker.isAllowed(any(), any()) } returns false
        assertEquals(403, call("GET", "/api/admin/orders").status)
    }

    @Test
    fun `put, patch and delete are checked`() {
        every { checker.isAllowed(any(), any()) } returns false
        listOf("PUT", "PATCH", "DELETE").forEach { assertEquals(403, call(it, "/api/orders/1").status) }
    }

    @Test
    fun `an allowed account passes a mutating call`() {
        every { checker.isAllowed(any(), any()) } returns true
        assertEquals(200, call("POST", "/api/orders").status)
    }

    @Test
    fun `a percent-encoded admin path is still checked`() {
        every { checker.isAllowed(any(), any()) } returns false
        assertEquals(403, call("GET", "/api/%61dmin/orders").status)
    }
}
