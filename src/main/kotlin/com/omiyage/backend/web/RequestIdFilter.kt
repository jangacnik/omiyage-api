package com.omiyage.backend.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

private const val REQUEST_ID_HEADER = "X-Request-Id"
private const val REQUEST_ID_MDC_KEY = "requestId"
private val SAFE_REQUEST_ID = Regex("^[A-Za-z0-9-]{1,64}$")

/** Tags every log line of a request with a request id (the caller's, if well-formed) and echoes it back. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestIdFilter : OncePerRequestFilter() {

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        val requestId = request.getHeader(REQUEST_ID_HEADER)?.takeIf { SAFE_REQUEST_ID.matches(it) } ?: UUID.randomUUID().toString()
        MDC.put(REQUEST_ID_MDC_KEY, requestId)
        response.setHeader(REQUEST_ID_HEADER, requestId)
        try {
            filterChain.doFilter(request, response)
        } finally {
            MDC.remove(REQUEST_ID_MDC_KEY)
        }
    }
}
