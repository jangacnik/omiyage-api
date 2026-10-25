package com.omiyage.backend.security

import com.fasterxml.jackson.databind.ObjectMapper
import com.omiyage.backend.web.errorBody
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.net.URLDecoder
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

private val REJECTED_LOG_INTERVAL = Duration.ofSeconds(30)
private val STATE_CHANGING_METHODS = setOf("POST", "PUT", "PATCH", "DELETE")

/** [accountStatus] is optional only so web-layer test slices, which do not load it, can omit it. */
@Component
class JwtAuthenticationFilter(
    private val jwtVerifier: JwtVerifier,
    private val accountStatus: AccountStatusChecker? = null,
    private val objectMapper: ObjectMapper,
) : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(JwtAuthenticationFilter::class.java)
    private val rejectedSinceLastLog = AtomicInteger()
    private var lastRejectedLog = Instant.EPOCH

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        val header = request.getHeader("Authorization")
        if (header != null && header.startsWith("Bearer ")) {
            val token = header.removePrefix("Bearer ")
            try {
                val principal = jwtVerifier.verify(token)
                if (needsFreshStatus(request) && accountStatus?.isAllowed(token, principal) == false) {
                    writeForbidden(response)
                    return
                }
                val authorities = listOf(SimpleGrantedAuthority("ROLE_${principal.role.name}"))
                val authentication = UsernamePasswordAuthenticationToken(principal, null, authorities)
                SecurityContextHolder.getContext().authentication = authentication
            } catch (e: JwtVerificationException) {
                logRejected(request, e)
            }
        }
        filterChain.doFilter(request, response)
    }

    private fun needsFreshStatus(request: HttpServletRequest): Boolean =
        request.method in STATE_CHANGING_METHODS || decodedPath(request).startsWith("/api/admin/")

    // Spring MVC routes on the decoded path, so match on it too (/api/%61dmin/... is the admin API).
    private fun decodedPath(request: HttpServletRequest): String =
        URLDecoder.decode(request.requestURI, Charsets.UTF_8)

    private fun writeForbidden(response: HttpServletResponse) {
        response.status = 403
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.writer.write(objectMapper.writeValueAsString(errorBody(HttpStatus.FORBIDDEN, "account is not allowed")))
    }

    // Bad tokens can be hammered at will, so warn at most once per interval with a count; never log the token.
    @Synchronized
    private fun logRejected(request: HttpServletRequest, e: JwtVerificationException) {
        val rejected = rejectedSinceLastLog.incrementAndGet()
        val now = Instant.now()
        if (lastRejectedLog.plus(REJECTED_LOG_INTERVAL) > now) return
        lastRejectedLog = now
        log.warn("rejected {} token(s) since last report, latest on {} {}: {}", rejected, request.method, request.requestURI, e.message)
        rejectedSinceLastLog.set(0)
    }
}
