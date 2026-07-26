package com.omiyage.backend.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
class JwtAuthenticationFilter(private val jwtVerifier: JwtVerifier) : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(JwtAuthenticationFilter::class.java)

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        val header = request.getHeader("Authorization")
        if (header != null && header.startsWith("Bearer ")) {
            try {
                val principal = jwtVerifier.verify(header.removePrefix("Bearer "))
                val authorities = listOf(SimpleGrantedAuthority("ROLE_${principal.role.name}"))
                val authentication = UsernamePasswordAuthenticationToken(principal, null, authorities)
                SecurityContextHolder.getContext().authentication = authentication
            } catch (e: JwtVerificationException) {
                log.debug("rejected token on {} {}: {}", request.method, request.requestURI, e.message)
            }
        }
        filterChain.doFilter(request, response)
    }
}
