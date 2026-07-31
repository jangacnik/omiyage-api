package com.omiyage.backend.user

import com.fasterxml.jackson.databind.ObjectMapper
import com.omiyage.backend.config.MeishiProperties
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpStatusCodeException
import org.springframework.web.client.RestClient
import org.springframework.web.client.body
import org.springframework.web.server.ResponseStatusException

private data class MeishiErrorResponse(val error: String? = null)

/** Proxies /api/users/me to MeishiApi, which is now the source of truth for profile data. */
@Component
class MeishiProfileClient(props: MeishiProperties, private val objectMapper: ObjectMapper) {

    private val client = RestClient.builder().baseUrl(props.apiUrl).build()

    fun getMe(authHeader: String): UserProfileDto = execute {
        client.get().uri("/api/users/me").header("Authorization", authHeader).retrieve()
    }

    fun updateMe(authHeader: String, updates: Map<String, Any?>): UserProfileDto = execute {
        client.patch().uri("/api/users/me").header("Authorization", authHeader).body(updates).retrieve()
    }

    private fun execute(call: () -> RestClient.ResponseSpec): UserProfileDto {
        try {
            return call().body<UserProfileDto>()
                ?: throw ResponseStatusException(org.springframework.http.HttpStatus.BAD_GATEWAY, "empty response from meishi")
        } catch (e: HttpStatusCodeException) {
            val message = try {
                objectMapper.readValue(e.responseBodyAsByteArray, MeishiErrorResponse::class.java).error
            } catch (parseError: Exception) {
                null
            } ?: e.statusText
            throw ResponseStatusException(e.statusCode, message)
        }
    }
}
