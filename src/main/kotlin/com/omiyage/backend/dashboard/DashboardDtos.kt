package com.omiyage.backend.dashboard

import com.omiyage.backend.common.Currency
import com.omiyage.backend.order.OrderDto
import com.omiyage.backend.user.UserProfileDto
import java.math.BigDecimal

data class PendingAmountDto(val currency: Currency, val amount: BigDecimal)

data class UserDashboardEntryDto(
    val user: UserProfileDto,
    // Non-delivered orders only - delivered ones are settled history, fetched lazily per user via
    // DashboardService.deliveredOrdersForUser instead of bloating every dashboard load.
    val orders: List<OrderDto>,
    val deliveredOrderCount: Int,
    // Unpaid items' actualPrice (JPY, converted) or maxPrice (EUR) while not yet bought, summed
    // into EUR - a list for response-shape stability, but always at most one entry now that every
    // item price normalizes to EUR.
    val pendingAmounts: List<PendingAmountDto>,
)
