package com.omiyage.backend.auth

import com.omiyage.backend.config.MeishiProperties
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.Validator
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

data class LoginRequest(
    @field:NotBlank @field:Size(max = 100) val nickname: String,
    @field:NotBlank val password: String,
) {
    override fun toString() = "LoginRequest(redacted)"
}

data class MeishiExchangeRequest(
    @field:NotBlank @field:Size(max = 512) val code: String,
    @field:NotBlank @field:Size(min = 43, max = 128) @field:Pattern(regexp = "^[A-Za-z0-9\\-._~]+$") val codeVerifier: String,
    @field:NotBlank @field:Size(max = 2048) val redirectUri: String,
) {
    override fun toString() = "MeishiExchangeRequest(redacted)"
}

data class TokenResponse(val token: String)

/** [authorizeUrl] and [clientId] are only set while [enabled], so a client can build the hosted-login link from this alone. */
data class MeishiSsoConfig(val enabled: Boolean, val authorizeUrl: String?, val clientId: String?)

data class PublicConfigResponse(val meishiSso: MeishiSsoConfig)

@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val service: AuthService,
    private val rateLimiter: LoginRateLimiter,
    private val meishi: MeishiProperties,
    private val validator: Validator,
) {

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

    @GetMapping("/config")
    fun publicConfig(): PublicConfigResponse {
        val enabled = meishi.ssoActive
        return PublicConfigResponse(
            MeishiSsoConfig(enabled, meishi.ssoAuthorizeUrl.takeIf { enabled }, meishi.ssoClientId.takeIf { enabled }),
        )
    }

    @PostMapping("/meishi/exchange")
    fun meishiExchange(@RequestBody request: MeishiExchangeRequest, httpRequest: HttpServletRequest): TokenResponse {
        // Checked before validation so a disabled flow is 404 whatever the body looks like.
        if (!meishi.ssoActive) throw ResponseStatusException(HttpStatus.NOT_FOUND, "meishi sso is disabled")
        if (validator.validate(request).isNotEmpty()) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid request")
        val ip = clientIp(httpRequest)
        try {
            rateLimiter.checkAttempt(ip)
        } catch (e: ResponseStatusException) {
            securityLog.warn("login_rate_limited clientIp={}", ip)
            throw e
        }
        val token = service.loginWithMeishiCode(meishi.ssoClientId, request.code, request.codeVerifier, request.redirectUri, ip)
        return TokenResponse(token)
    }

    // Behind the ingress the last X-Forwarded-For entry is the address the ingress itself saw;
    // earlier entries are client-supplied and spoofable.
    private fun clientIp(request: HttpServletRequest): String =
        request.getHeader("X-Forwarded-For")?.substringAfterLast(',')?.trim()?.ifEmpty { null } ?: request.remoteAddr
}
