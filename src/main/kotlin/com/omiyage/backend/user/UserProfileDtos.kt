package com.omiyage.backend.user

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import jakarta.validation.constraints.NotBlank
import java.util.UUID

@JsonIgnoreProperties(ignoreUnknown = true)
data class UserProfileDto(
    val id: UUID,
    // Null until the user activates their account (sets their own nickname) - MeishiApi returns
    // a real null here for any not-yet-activated user, so this must stay nullable or Jackson
    // throws deserializing the admin user list the moment one such account exists.
    val nickname: String?,
    val email: String?,
    val phone: String?,
    val firstName: String?,
    val lastName: String?,
    val addressLine1: String?,
    val addressLine2: String?,
    val postalCode: String?,
    val city: String?,
    val country: String?,
    val avatarKey: String? = null,
    val activated: Boolean = true,
)

data class ChangeNicknameRequest(@field:NotBlank val nickname: String)

data class ChangePasswordRequest(
    @field:NotBlank val oldPassword: String,
    @field:NotBlank val newPassword: String,
)
