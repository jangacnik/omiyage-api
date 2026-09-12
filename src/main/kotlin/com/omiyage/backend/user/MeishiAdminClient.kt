package com.omiyage.backend.user

import com.omiyage.backend.config.MeishiProperties
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.web.client.HttpStatusCodeException
import org.springframework.web.client.RestClient
import org.springframework.web.client.body
import org.springframework.web.server.ResponseStatusException
import org.springframework.stereotype.Component

/** Proxies MeishiApi's admin-only user list - the current admin's own bearer token is forwarded
 *  as-is, exactly like MeishiProfileClient does for /api/users/me. */
@Component
class MeishiAdminClient(props: MeishiProperties, private val objectMapper: ObjectMapper) {

    private val client = RestClient.builder().baseUrl(props.apiUrl).build()

    fun listUsers(authHeader: String): List<UserProfileDto> {
        try {
            return client.get().uri("/api/users").header("Authorization", authHeader).retrieve()
                .body<List<UserProfileDto>>() ?: emptyList()
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
