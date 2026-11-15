package com.omiyage.backend.security

import com.omiyage.backend.order.SIGNATURE_HEADER
import com.omiyage.backend.order.TIMESTAMP_HEADER
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

const val BOT_ROLE = "BOT"
private const val BOT_PRINCIPAL = "omiyage-bot"

/** Authenticates a request as the bot when its signature verifies. Otherwise it leaves the request
 *  anonymous, and the bot filter chain answers 401. Not a bean: it only ever runs inside that chain. */
class BotSignatureFilter(private val verifier: RequestSignatureVerifier) : OncePerRequestFilter() {

    private val securityLog = LoggerFactory.getLogger("security")

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        val signed = SignedRequest(
            timestampHeader = request.getHeader(TIMESTAMP_HEADER),
            signatureHeader = request.getHeader(SIGNATURE_HEADER),
            method = request.method,
            path = request.requestURI,
            query = request.queryString.orEmpty(),
        )
        verifier.verify(signed).fold(
            { error -> securityLog.warn("bot_request_rejected method={} path={} reason={}", request.method, request.requestURI, error) },
            {
                SecurityContextHolder.getContext().authentication =
                    UsernamePasswordAuthenticationToken(BOT_PRINCIPAL, null, listOf(SimpleGrantedAuthority("ROLE_$BOT_ROLE")))
            },
        )
        filterChain.doFilter(request, response)
    }
}
