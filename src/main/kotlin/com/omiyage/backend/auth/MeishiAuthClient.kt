package com.omiyage.backend.auth

import com.omiyage.backend.config.MeishiProperties
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.body
import org.springframework.web.server.ResponseStatusException

data class MeishiLoginRequest(val nickname: String, val password: String)
data class MeishiTokenResponse(val token: String)
data class MeishiUserResponse(val id: String, val nickname: String?, val role: String, val status: String)

@Component
class MeishiAuthClient(props: MeishiProperties) {

    private val client = RestClient.builder().baseUrl(props.apiUrl).build()

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
