package com.omiyage.backend.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.slf4j.event.Level
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.servlet.HandlerMapping

const val USER_ID_MDC_KEY = "userId"
const val ROLE_MDC_KEY = "role"
private const val ANONYMOUS = "anonymous"
private const val UNMATCHED_ROUTE = "unmatched"
private val WARN_STATUSES = setOf(401, 403, 429)
private val PROBE_PREFIXES = listOf("/actuator/health", "/health", "/ready")

/** One structured "access" line per request: matched route (never the URI or query), status, duration, caller. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
class AccessLogFilter(
    @Value("\${omiyage.logging.slow-request-ms:2000}") private val slowRequestMs: Long,
) : OncePerRequestFilter() {

    private val accessLog = LoggerFactory.getLogger("access")
    private val slowLog = LoggerFactory.getLogger("slow")

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        val startNanos = System.nanoTime()
        try {
            filterChain.doFilter(request, response)
        } catch (e: Exception) {
            response.status = HttpServletResponse.SC_INTERNAL_SERVER_ERROR
            throw e
        } finally {
            val durationMs = (System.nanoTime() - startNanos) / 1_000_000
            logAccess(request, response.status, durationMs)
            MDC.remove(USER_ID_MDC_KEY)
            MDC.remove(ROLE_MDC_KEY)
        }
    }

    private fun logAccess(request: HttpServletRequest, status: Int, durationMs: Long) {
        val route = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE) as? String ?: UNMATCHED_ROUTE
        val fields = mapOf(
            "method" to request.method,
            "route" to route,
            "status" to status.toString(),
            "durationMs" to durationMs.toString(),
            USER_ID_MDC_KEY to (MDC.get(USER_ID_MDC_KEY) ?: ANONYMOUS),
            ROLE_MDC_KEY to (MDC.get(ROLE_MDC_KEY) ?: ANONYMOUS),
        )
        withFields(fields) {
            when (levelFor(request.requestURI, status)) {
                Level.ERROR -> accessLog.error("request")
                Level.WARN -> accessLog.warn("request")
                Level.DEBUG -> accessLog.debug("request")
                else -> accessLog.info("request")
            }
            if (durationMs > slowRequestMs) slowLog.warn("slow_request")
        }
    }

    private fun levelFor(uri: String, status: Int): Level = when {
        PROBE_PREFIXES.any { uri.startsWith(it) } -> Level.DEBUG
        status >= 500 -> Level.ERROR
        status in WARN_STATUSES -> Level.WARN
        else -> Level.INFO
    }

    private fun withFields(fields: Map<String, String>, block: () -> Unit) {
        val previous = fields.keys.associateWith { MDC.get(it) }
        fields.forEach { (key, value) -> MDC.put(key, value) }
        try {
            block()
        } finally {
            previous.forEach { (key, value) -> if (value == null) MDC.remove(key) else MDC.put(key, value) }
        }
    }
}
