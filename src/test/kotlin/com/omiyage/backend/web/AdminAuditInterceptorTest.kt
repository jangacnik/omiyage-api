package com.omiyage.backend.web

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.omiyage.backend.security.MeishiPrincipal
import com.omiyage.backend.security.Role
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import java.util.UUID

class AdminAuditInterceptorTest {

    private val interceptor = AdminAuditInterceptor()
    private val appender = ListAppender<ILoggingEvent>()
    private val auditLogger = LoggerFactory.getLogger("audit") as Logger
    private var originalLevel: Level? = null

    @BeforeEach
    fun attachAppender() {
        originalLevel = auditLogger.level
        auditLogger.level = Level.INFO
        appender.start()
        auditLogger.addAppender(appender)
    }

    @AfterEach
    fun detachAppender() {
        auditLogger.detachAppender(appender)
        auditLogger.level = originalLevel
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `logs who did what for a write request`() {
        val admin = MeishiPrincipal(UUID.randomUUID(), "root", Role.ADMIN)
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(admin, null, emptyList())
        val response = MockHttpServletResponse().apply { status = 204 }

        interceptor.afterCompletion(routed("DELETE", "/api/admin/orders/{id}"), response, Any(), null)

        assertEquals(1, appender.list.size)
        val message = appender.list.single().formattedMessage
        assertEquals("admin_mutation user=${admin.id} method=DELETE route=/api/admin/orders/{id} status=204", message)
    }

    @Test
    fun `does not log read-only requests`() {
        for (method in listOf("GET", "HEAD", "OPTIONS")) {
            interceptor.afterCompletion(MockHttpServletRequest(method, "/api/admin/orders"), MockHttpServletResponse(), Any(), null)
        }

        assertTrue(appender.list.isEmpty())
    }

    @Test
    fun `logs a null user when nobody is authenticated`() {
        interceptor.afterCompletion(routed("POST", "/api/admin/orders"), MockHttpServletResponse(), Any(), null)

        assertEquals("admin_mutation user=null method=POST route=/api/admin/orders status=200", appender.list.single().formattedMessage)
    }

    private fun routed(method: String, pattern: String) = MockHttpServletRequest(method, pattern).apply {
        setAttribute(org.springframework.web.servlet.HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, pattern)
    }
}
