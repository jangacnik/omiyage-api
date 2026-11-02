package com.omiyage.backend.web

import ch.qos.logback.classic.Level
import com.omiyage.backend.logging.LogCapture
import jakarta.servlet.FilterChain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.servlet.HandlerMapping

class AccessLogFilterTest {

    private lateinit var capture: LogCapture
    private val filter = AccessLogFilter(slowRequestMs = 2000)

    @BeforeEach
    fun attach() {
        capture = LogCapture.root()
    }

    @AfterEach
    fun detach() {
        capture.close()
        MDC.clear()
    }

    private fun run(
        uri: String,
        status: Int = 200,
        route: String? = null,
        chain: FilterChain = FilterChain { _, res -> (res as MockHttpServletResponse).status = status },
    ) {
        val request = MockHttpServletRequest("GET", uri)
        request.queryString = "token=CANARY_QUERY"
        route?.let { request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, it) }
        filter.doFilter(request, MockHttpServletResponse(), chain)
    }

    private fun access() = capture.named("access").single()

    @Test
    fun `logs matched route status duration and anonymous caller once`() {
        run("/api/orders/123", route = "/api/orders/{id}")

        val event = access()
        assertEquals(Level.INFO, event.level)
        assertEquals("/api/orders/{id}", event.mdcPropertyMap["route"])
        assertEquals("GET", event.mdcPropertyMap["method"])
        assertEquals("200", event.mdcPropertyMap["status"])
        assertEquals("anonymous", event.mdcPropertyMap["userId"])
        assertEquals("anonymous", event.mdcPropertyMap["role"])
        assertTrue(event.mdcPropertyMap["durationMs"]!!.toLong() >= 0)
    }

    @Test
    fun `logs unmatched for a 404 and never the uri or query`() {
        run("/api/secret-path/CANARY_PATH", status = 404)

        assertEquals("unmatched", access().mdcPropertyMap["route"])
        assertFalse(capture.text().contains("CANARY"))
    }

    @Test
    fun `uses warn for 401 403 and 429`() {
        listOf(401, 403, 429).forEach { run("/x", status = it) }

        assertEquals(listOf(Level.WARN, Level.WARN, Level.WARN), capture.named("access").map { it.level })
    }

    @Test
    fun `uses error for a 5xx response`() {
        run("/x", status = 503)

        assertEquals(Level.ERROR, access().level)
    }

    @Test
    fun `an exception escaping the chain is logged as a 500 and rethrown`() {
        assertThrows(IllegalStateException::class.java) { run("/x", chain = FilterChain { _, _ -> error("boom") }) }

        assertEquals("500", access().mdcPropertyMap["status"])
        assertEquals(Level.ERROR, access().level)
    }

    @Test
    fun `probes are logged at debug`() {
        run("/actuator/health/liveness")

        assertEquals(Level.DEBUG, access().level)
    }

    @Test
    fun `reports the authenticated user from the mdc and clears it afterwards`() {
        val chain = FilterChain { _, _ ->
            MDC.put(USER_ID_MDC_KEY, "user-1")
            MDC.put(ROLE_MDC_KEY, "ADMIN")
        }
        run("/x", chain = chain)

        assertEquals("user-1", access().mdcPropertyMap["userId"])
        assertEquals("ADMIN", access().mdcPropertyMap["role"])
        assertNull(MDC.get(USER_ID_MDC_KEY))
        assertNull(MDC.get(ROLE_MDC_KEY))
    }

    @Test
    fun `warns separately about slow requests`() {
        val slowFilter = AccessLogFilter(slowRequestMs = -1)

        slowFilter.doFilter(MockHttpServletRequest("GET", "/x"), MockHttpServletResponse(), FilterChain { _, _ -> })

        assertEquals(1, capture.named("access").size)
        assertEquals(Level.WARN, capture.named("slow").single().level)
    }
}
