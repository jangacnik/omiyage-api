package com.omiyage.backend.web

import jakarta.servlet.FilterChain
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class RequestIdFilterTest {

    private val filter = RequestIdFilter()

    private fun run(requestId: String?): Pair<MockHttpServletResponse, String?> {
        val request = MockHttpServletRequest("GET", "/api/ping")
        requestId?.let { request.addHeader("X-Request-Id", it) }
        val response = MockHttpServletResponse()
        var seenInChain: String? = null
        val chain = FilterChain { _, _ -> seenInChain = MDC.get("requestId") }
        filter.doFilter(request, response, chain)
        return response to seenInChain
    }

    @Test
    fun `reuses a well-formed caller request id and clears the mdc afterwards`() {
        val (response, seen) = run("abc-123")

        assertEquals("abc-123", seen)
        assertEquals("abc-123", response.getHeader("X-Request-Id"))
        assertNull(MDC.get("requestId"))
    }

    @Test
    fun `replaces a malformed request id with a generated one`() {
        val (response, seen) = run("bad id\nwith newline")

        assertNotEquals("bad id\nwith newline", seen)
        assertEquals(seen, response.getHeader("X-Request-Id"))
    }
}
