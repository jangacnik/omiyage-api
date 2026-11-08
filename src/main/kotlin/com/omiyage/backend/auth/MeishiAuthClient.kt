package com.omiyage.backend.auth

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.fasterxml.jackson.annotation.JsonProperty
import com.omiyage.backend.errors.AuthError
import com.omiyage.backend.errors.DomainError
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import org.springframework.web.client.body

data class MeishiLoginRequest(val nickname: String, val password: String)
data class MeishiTokenResponse(val token: String)
data class MeishiOAuthTokenResponse(@JsonProperty("access_token") val accessToken: String?)
data class MeishiUserResponse(val id: String, val nickname: String?, val role: String, val status: String)

@Component
class MeishiAuthClient(private val client: RestClient) {

    fun login(nickname: String, password: String): Either<DomainError, String> {
        val response = try {
            client.post()
                .uri("/api/auth/login")
                .body(MeishiLoginRequest(nickname, password))
                .retrieve()
                .body<MeishiTokenResponse>()
        } catch (e: org.springframework.web.client.HttpClientErrorException) {
            return AuthError.InvalidCredentials.left()
        }
        return response?.token?.right() ?: AuthError.InvalidCredentials.left()
    }

    /** Redeems a hosted-login authorization code at MeishiApi, which hands back the usual Meishi JWT. */
    fun exchangeCode(clientId: String, code: String, codeVerifier: String, redirectUri: String): Either<DomainError, String> {
        val form = LinkedMultiValueMap<String, String>().apply {
            add("grant_type", "authorization_code")
            add("client_id", clientId)
            add("code", code)
            add("code_verifier", codeVerifier)
            add("redirect_uri", redirectUri)
        }
        val response = try {
            client.post()
                .uri("/api/oauth/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body<MeishiOAuthTokenResponse>()
        } catch (e: org.springframework.web.client.HttpClientErrorException) {
            return when (e.statusCode.value()) {
                HttpStatus.NOT_FOUND.value() -> AuthError.SsoNotEnabledUpstream.left()
                HttpStatus.TOO_MANY_REQUESTS.value() -> AuthError.TooManyAttempts.left()
                else -> AuthError.InvalidAuthorizationCode.left()
            }
        }
        return response?.accessToken?.right() ?: AuthError.InvalidAuthorizationCode.left()
    }

    fun getMe(token: String): Either<DomainError, MeishiUserResponse> {
        val response = try {
            client.get()
                .uri("/api/users/me")
                .header("Authorization", "Bearer $token")
                .retrieve()
                .body<MeishiUserResponse>()
        } catch (e: org.springframework.web.client.HttpClientErrorException) {
            return AuthError.InvalidCredentials.left()
        }
        return response?.right() ?: AuthError.InvalidCredentials.left()
    }
}
