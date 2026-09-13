package com.omiyage.backend.user

import com.omiyage.backend.config.MeishiProperties
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.web.client.HttpStatusCodeException
import org.springframework.web.client.RestClient
import org.springframework.web.client.body
import org.springframework.web.server.ResponseStatusException
import org.springframework.stereotype.Component
import java.util.UUID

data class NicknameDto(val id: UUID, val nickname: String?)

/** Proxies MeishiApi's member-facing nickname directory (id + nickname only, no other profile
 *  fields) - unlike MeishiAdminClient, this route is open to any authenticated caller, so the
 *  caller's own bearer token works here even for a non-admin member. */
@Component
class MeishiNicknameClient(props: MeishiProperties, private val objectMapper: ObjectMapper) {

    private val client = RestClient.builder().baseUrl(props.apiUrl).build()

    fun nicknames(authHeader: String): List<NicknameDto> {
        try {
            return client.get().uri("/api/users/nicknames").header("Authorization", authHeader).retrieve()
                .body<List<NicknameDto>>() ?: emptyList()
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
