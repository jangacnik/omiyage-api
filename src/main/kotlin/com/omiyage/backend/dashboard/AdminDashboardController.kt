package com.omiyage.backend.dashboard

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/admin/dashboard")
class AdminDashboardController(private val service: DashboardService) {

    @GetMapping("/users")
    fun userDashboard(@RequestHeader("Authorization") authHeader: String): List<UserDashboardEntryDto> =
        service.userDashboard(authHeader)
}
