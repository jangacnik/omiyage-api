package com.omiyage.backend.auth

import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestClient
import org.springframework.web.client.body
import org.springframework.web.server.ResponseStatusException

data class MeishiLoginRequest(val nickname: String, val password: String)
data class MeishiTokenResponse(val token: String)
data class MeishiOAuthTokenResponse(@JsonProperty("access_token") val accessToken: String?)
data class MeishiUserResponse(val id: String, val nickname: String?, val role: String, val status: String)

@Component
class MeishiAuthClient(private val client: RestClient) {

    fun login(nickname: String, password: String): String {
        val response = try {
            client.post()
                .uri("/api/auth/login")
                .body(MeishiLoginRequest(nickname, password))
                .retrieve()
                .body<MeishiTokenResponse>()
        } catch (e: org.springframework.web.client.HttpClientErrorException) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid credentials")
        }
        return response?.token ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid credentials")
    }

    /** Redeems a hosted-login authorization code at MeishiApi, which hands back the usual Meishi JWT. */
    fun exchangeCode(clientId: String, code: String, codeVerifier: String, redirectUri: String): String {
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
            throw when (e.statusCode.value()) {
                HttpStatus.NOT_FOUND.value() -> ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "meishi sso is not enabled")
                HttpStatus.TOO_MANY_REQUESTS.value() -> ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "too many login attempts")
                else -> ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid authorization code")
            }
        }
        return response?.accessToken ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid authorization code")
    }

    fun getMe(token: String): MeishiUserResponse {
        val response = try {
            client.get()
                .uri("/api/users/me")
                .header("Authorization", "Bearer $token")
                .retrieve()
                .body<MeishiUserResponse>()
        } catch (e: org.springframework.web.client.HttpClientErrorException) {
            throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid credentials")
        }
        return response ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid credentials")
    }
}
