package com.omiyage.backend.user

import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/users/me")
class UserProfileController(private val client: MeishiProfileClient) {

    @GetMapping
    fun getMe(@RequestHeader("Authorization") authHeader: String): UserProfileDto =
        client.getMe(authHeader)

    @PatchMapping
    fun updateMe(
        @RequestHeader("Authorization") authHeader: String,
        @RequestBody updates: Map<String, Any?>,
    ): UserProfileDto = client.updateMe(authHeader, updates)

    @PatchMapping("/nickname")
    fun changeNickname(
        @RequestHeader("Authorization") authHeader: String,
        @Valid @RequestBody request: ChangeNicknameRequest,
    ): UserProfileDto = client.changeNickname(authHeader, request.nickname)

    @PatchMapping("/password")
    fun changePassword(
        @RequestHeader("Authorization") authHeader: String,
        @Valid @RequestBody request: ChangePasswordRequest,
    ): ResponseEntity<Void> {
        client.changePassword(authHeader, request.oldPassword, request.newPassword)
        return ResponseEntity.noContent().build()
    }
}
