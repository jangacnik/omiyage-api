package com.omiyage.backend.user

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
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
}
