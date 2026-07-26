package com.omiyage.backend.user

import com.omiyage.backend.security.CurrentUser
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/users/me")
class UserProfileController(private val service: UserProfileService) {

    @GetMapping
    fun getMe(): UserProfileResponse = service.ensureProfile(CurrentUser.principal()).toResponse()

    @PatchMapping
    fun updateMe(@Valid @RequestBody request: UpdateProfileRequest): UserProfileResponse =
        service.updateProfile(CurrentUser.principal(), request.email, request.phone, request.shippingAddress).toResponse()
}
