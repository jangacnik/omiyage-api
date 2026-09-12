package com.omiyage.backend.dashboard

import com.omiyage.backend.common.Currency
import com.omiyage.backend.order.OrderDto
import com.omiyage.backend.user.UserProfileDto
import java.math.BigDecimal

data class PendingAmountDto(val currency: Currency, val amount: BigDecimal)

data class UserDashboardEntryDto(
    val user: UserProfileDto,
    val orders: List<OrderDto>,
    // Unpaid items' actualPrice (fallback maxPrice while not yet bought), grouped by currency -
    // orders can mix JPY and EUR items, so this is never collapsed into one number.
    val pendingAmounts: List<PendingAmountDto>,
)
