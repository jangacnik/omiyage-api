package com.omiyage.backend.dashboard

import com.omiyage.backend.common.Currency
import com.omiyage.backend.order.LOCKED_ITEM_STATUSES
import com.omiyage.backend.order.OrderRepository
import com.omiyage.backend.order.OrderStatus
import com.omiyage.backend.order.toDto
import com.omiyage.backend.shipment.ExchangeRateService
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
    private val exchangeRates: ExchangeRateService,
) {

    @Transactional(readOnly = true)
    fun userDashboard(authHeader: String): List<UserDashboardEntryDto> =
        // An unactivated shell account (created via the admin "create user" flow but never
        // activated) has no nickname and can't have placed an order under a real session - skip
        // it rather than show a nameless, order-less row.
        meishiAdmin.listUsers(authHeader).filter { it.activated }.map { user -> entryFor(user) }

    private fun entryFor(user: UserProfileDto): UserDashboardEntryDto {
        val userOrders = orders.findByUserId(user.id, Pageable.unpaged()).content
        val pending = userOrders
            .flatMap { order -> order.items.map { item -> order to item } }
            // A cancelled order's items that were never actually bought owe nothing - but if an
            // item was already BOUGHT/SHIPPED before the order got cancelled, that money was
            // still spent and must keep showing up as owed until marked paid.
            .filter { (order, item) -> order.status != OrderStatus.CANCELLED || item.status in LOCKED_ITEM_STATUSES }
            .map { (_, item) -> item }
            .filter { !it.paid }
            // actualPrice is always JPY, maxPrice is always EUR (see OrderService.updateItem /
            // buildOrderItem) - convert the JPY side before summing so this is a real EUR total,
            // not JPY and EUR numbers added together.
            .sumOf { item ->
                val amount = item.actualPrice?.let(exchangeRates::toEur) ?: item.maxPrice ?: BigDecimal.ZERO
                amount * BigDecimal(item.quantity)
            }
            // Explicit scale: toEur() returns scale-2, but maxPrice may not be, and BigDecimal
            // arithmetic takes the larger of the two scales it's mixing.
            .setScale(2, java.math.RoundingMode.HALF_UP)
            .let { total -> if (total > BigDecimal.ZERO) listOf(PendingAmountDto(Currency.EUR, total)) else emptyList() }

        return UserDashboardEntryDto(
            user = user,
            orders = userOrders.map { it.toDto(includeProfit = true, toEur = exchangeRates::toEur) },
            pendingAmounts = pending,
        )
    }
}
