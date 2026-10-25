package com.omiyage.backend.auth

import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class LoginRequest(
    @field:NotBlank @field:Size(max = 100) val nickname: String,
    @field:NotBlank val password: String,
)

data class TokenResponse(val token: String)

@RestController
@RequestMapping("/api/auth")
class AuthController(private val service: AuthService, private val rateLimiter: LoginRateLimiter) {

    @PostMapping("/login")
    fun login(@Valid @RequestBody request: LoginRequest, httpRequest: HttpServletRequest): TokenResponse {
        rateLimiter.checkAttempt(clientIp(httpRequest), request.nickname)
        return TokenResponse(service.login(request.nickname, request.password))
    }

    // Behind the ingress the last X-Forwarded-For entry is the address the ingress itself saw;
    // earlier entries are client-supplied and spoofable.
    private fun clientIp(request: HttpServletRequest): String =
        request.getHeader("X-Forwarded-For")?.substringAfterLast(',')?.trim()?.ifEmpty { null } ?: request.remoteAddr
}
