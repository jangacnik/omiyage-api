package com.omiyage.backend.dashboard

import com.omiyage.backend.order.OrderRepository
import com.omiyage.backend.order.OrderStatus
import com.omiyage.backend.order.toDto
import com.omiyage.backend.user.MeishiAdminClient
import com.omiyage.backend.user.UserProfileDto
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

@Service
class DashboardService(
    private val meishiAdmin: MeishiAdminClient,
    private val orders: OrderRepository,
) {

    @Transactional(readOnly = true)
    fun userDashboard(authHeader: String): List<UserDashboardEntryDto> =
        meishiAdmin.listUsers(authHeader).map { user -> entryFor(user) }

    private fun entryFor(user: UserProfileDto): UserDashboardEntryDto {
        val userOrders = orders.findByUserId(user.id, Pageable.unpaged()).content
        val pending = userOrders
            .filter { it.status != OrderStatus.CANCELLED }
            .flatMap { it.items }
            .filter { !it.paid }
            .groupBy({ it.currency }, { it.actualPrice ?: it.maxPrice ?: BigDecimal.ZERO })
            .map { (currency, amounts) -> PendingAmountDto(currency, amounts.fold(BigDecimal.ZERO, BigDecimal::add)) }
            .filter { it.amount > BigDecimal.ZERO }
            .sortedBy { it.currency }

        return UserDashboardEntryDto(
            user = user,
            orders = userOrders.map { it.toDto() },
            pendingAmounts = pending,
        )
    }
}
