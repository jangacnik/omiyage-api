package com.omiyage.backend.dashboard

import com.omiyage.backend.order.OrderDto
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/admin/dashboard")
class AdminDashboardController(private val service: DashboardService) {

    @GetMapping("/users")
    fun userDashboard(@RequestHeader("Authorization") authHeader: String): List<UserDashboardEntryDto> =
        service.userDashboard(authHeader)

    @GetMapping("/users/{userId}/delivered-orders")
    fun deliveredOrders(@PathVariable userId: UUID): List<OrderDto> = service.deliveredOrdersForUser(userId)
}
