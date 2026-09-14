package com.omiyage.backend.dashboard

import com.omiyage.backend.common.Currency
import com.omiyage.backend.order.LOCKED_ITEM_STATUSES
import com.omiyage.backend.order.OrderDto
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
import java.util.UUID

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

    // Delivered orders are settled history, not something the dashboard needs on every load -
    // the frontend fetches this per user only once an admin actually expands that section.
    @Transactional(readOnly = true)
    fun deliveredOrdersForUser(userId: UUID): List<OrderDto> =
        orders.findByUserId(userId, Pageable.unpaged()).content
            .filter { it.status == OrderStatus.DELIVERED }
            .map { it.toDto(includeProfit = true, toEur = exchangeRates::toEur) }

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
            // What's owed is what the customer is charged - soldPrice, not the JPY actualPrice
            // (that's the admin's own cost basis). Both soldPrice and maxPrice are always EUR.
            .sumOf { item ->
                val amount = item.soldPrice ?: item.maxPrice ?: BigDecimal.ZERO
                amount * BigDecimal(item.quantity)
            }
            // Explicit scale: maxPrice/soldPrice may not already be scale-2, and BigDecimal
            // arithmetic takes the larger of the two scales it's mixing.
            .setScale(2, java.math.RoundingMode.HALF_UP)
            .let { total -> if (total > BigDecimal.ZERO) listOf(PendingAmountDto(Currency.EUR, total)) else emptyList() }

        // Delivered orders are settled - keep them out of the default payload (they're fetched
        // lazily per user, see deliveredOrdersForUser) but still count them so the accordion can
        // show a number before it's expanded.
        return UserDashboardEntryDto(
            user = user,
            orders = userOrders.filter { it.status != OrderStatus.DELIVERED }.map { it.toDto(includeProfit = true, toEur = exchangeRates::toEur) },
            deliveredOrderCount = userOrders.count { it.status == OrderStatus.DELIVERED },
            pendingAmounts = pending,
        )
    }
}
