package com.omiyage.backend.user

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.Size
import java.util.UUID

data class UserProfileResponse(
    val id: UUID,
    val nickname: String,
    val email: String?,
    val phone: String?,
    val shippingAddress: String?,
)

data class UpdateProfileRequest(
    @field:Email
    @field:Size(max = 255)
    val email: String? = null,

    @field:Size(max = 64)
    val phone: String? = null,

    @field:Size(max = 2000)
    val shippingAddress: String? = null,
)

fun UserProfile.toResponse() = UserProfileResponse(
    id = id,
    nickname = nickname,
    email = email,
    phone = phone,
    shippingAddress = shippingAddress,
)
