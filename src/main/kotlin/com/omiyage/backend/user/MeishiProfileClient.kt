package com.omiyage.backend.user

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.fasterxml.jackson.databind.ObjectMapper
import com.omiyage.backend.errors.DomainError
import com.omiyage.backend.errors.UserError
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.HttpStatusCodeException
import org.springframework.web.client.RestClient
import org.springframework.web.client.body

internal data class MeishiErrorResponse(val error: String? = null)

/** Proxies /api/users/me to MeishiApi, which is now the source of truth for profile data. */
@Component
class MeishiProfileClient(private val client: RestClient, private val objectMapper: ObjectMapper) {

    fun getMe(authHeader: String): Either<DomainError, UserProfileDto> = execute {
        client.get().uri("/api/users/me").header("Authorization", authHeader).retrieve()
    }

    fun updateMe(authHeader: String, updates: Map<String, Any?>): Either<DomainError, UserProfileDto> = execute {
        client.patch().uri("/api/users/me").header("Authorization", authHeader).body(updates).retrieve()
    }

    fun changeNickname(authHeader: String, nickname: String): Either<DomainError, UserProfileDto> = execute {
        client.patch().uri("/api/users/me/nickname").header("Authorization", authHeader)
            .body(mapOf("nickname" to nickname)).retrieve()
    }

    fun changePassword(authHeader: String, oldPassword: String, newPassword: String): Either<DomainError, Unit> {
        try {
            client.patch().uri("/api/users/me/password").header("Authorization", authHeader)
                .body(mapOf("oldPassword" to oldPassword, "newPassword" to newPassword)).retrieve()
                .toBodilessEntity()
        } catch (e: HttpClientErrorException) {
            return upstreamRejected(e).left()
        }
        return Unit.right()
    }

    private fun upstreamRejected(e: HttpStatusCodeException): UserError.UpstreamRejected {
        val message = try {
            objectMapper.readValue(e.responseBodyAsByteArray, MeishiErrorResponse::class.java).error
        } catch (parseError: Exception) {
            null
        } ?: e.statusText
        return UserError.UpstreamRejected(e.statusCode.value(), message)
    }

    private fun execute(call: () -> RestClient.ResponseSpec): Either<DomainError, UserProfileDto> {
        val profile = try {
            call().body<UserProfileDto>()
        } catch (e: HttpClientErrorException) {
            return upstreamRejected(e).left()
        }
        return profile?.right() ?: UserError.EmptyUpstreamResponse.left()
    }
}
