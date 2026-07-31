package com.omiyage.backend.user

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import java.util.UUID

@JsonIgnoreProperties(ignoreUnknown = true)
data class UserProfileDto(
    val id: UUID,
    val nickname: String,
    val email: String?,
    val phone: String?,
    val firstName: String?,
    val lastName: String?,
    val addressLine1: String?,
    val addressLine2: String?,
    val postalCode: String?,
    val city: String?,
    val country: String?,
)
