package com.omiyage.backend.auth

import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

data class LoginRequest(
    @field:NotBlank @field:Size(max = 100) val nickname: String,
    @field:NotBlank val password: String,
)

data class TokenResponse(val token: String)

@RestController
@RequestMapping("/api/auth")
class AuthController(private val service: AuthService, private val rateLimiter: LoginRateLimiter) {

    private val securityLog = LoggerFactory.getLogger("security")

    @PostMapping("/login")
    fun login(@Valid @RequestBody request: LoginRequest, httpRequest: HttpServletRequest): TokenResponse {
        val ip = clientIp(httpRequest)
        try {
            rateLimiter.checkAttempt(ip, request.nickname)
        } catch (e: ResponseStatusException) {
            securityLog.warn("login_rate_limited clientIp={}", ip)
            throw e
        }
        return TokenResponse(service.login(request.nickname, request.password, ip))
    }

    // Behind the ingress the last X-Forwarded-For entry is the address the ingress itself saw;
    // earlier entries are client-supplied and spoofable.
    private fun clientIp(request: HttpServletRequest): String =
        request.getHeader("X-Forwarded-For")?.substringAfterLast(',')?.trim()?.ifEmpty { null } ?: request.remoteAddr
}
