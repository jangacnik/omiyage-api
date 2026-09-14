package com.omiyage.backend.dashboard

import com.ninjasquad.springmockk.MockkBean
import com.omiyage.backend.PostgresIntegrationTest
import com.omiyage.backend.common.Condition
import com.omiyage.backend.common.Currency
import com.omiyage.backend.order.CreateOrderItemRequest
import com.omiyage.backend.order.CreateOrderRequest
import com.omiyage.backend.order.OrderItemStatus
import com.omiyage.backend.order.OrderService
import com.omiyage.backend.security.MeishiPrincipal
import com.omiyage.backend.shipment.ExchangeRateService
import com.omiyage.backend.security.Role
import com.omiyage.backend.user.MeishiAdminClient
import com.omiyage.backend.user.UserProfileDto
import io.mockk.every
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.util.UUID

@SpringBootTest
@Transactional
class DashboardServiceIntegrationTest : PostgresIntegrationTest() {

    @Autowired
    lateinit var dashboardService: DashboardService

    @Autowired
    lateinit var orderService: OrderService

    @Autowired
    lateinit var exchangeRateService: ExchangeRateService

    @MockkBean
    lateinit var meishiAdminClient: MeishiAdminClient

    private fun user() = MeishiPrincipal(UUID.randomUUID(), "bob", Role.USER)

    private fun profileFor(userId: UUID) = UserProfileDto(
        id = userId,
        nickname = "bob",
        email = null,
        phone = null,
        firstName = null,
        lastName = null,
        addressLine1 = null,
        addressLine2 = null,
        postalCode = null,
        city = null,
        country = null,
    )

    @Test
    fun `pending amount sums unpaid items, using actualPrice or falling back to maxPrice`() {
        exchangeRateService.setRate(BigDecimal("1.0000")) // 1:1 - this test is about the fallback logic, not conversion.
        val principal = user()
        every { meishiAdminClient.listUsers(any()) } returns listOf(profileFor(principal.id))

        val order = orderService.create(
            principal,
            CreateOrderRequest(
                items = listOf(
                    CreateOrderItemRequest(title = "Bought", condition = Condition.NEW, currency = Currency.JPY, maxPrice = BigDecimal("1000")),
                    CreateOrderItemRequest(title = "Not yet bought", condition = Condition.NEW, currency = Currency.JPY, maxPrice = BigDecimal("500")),
                ),
            ),
        )
        // Simulate the first item having actually been bought for less than its budgeted max.
        orderService.updateItem(order.id, order.items[0].id, OrderItemStatus.BOUGHT, BigDecimal("800"))

        val entry = dashboardService.userDashboard("Bearer irrelevant").single()

        assertEquals(1, entry.pendingAmounts.size)
        // 800 (actual, bought, JPY->EUR at 1:1) + 500 (maxPrice fallback, already EUR) = 1300
        assertEquals(BigDecimal("1300.00"), entry.pendingAmounts[0].amount)
        assertEquals(Currency.EUR, entry.pendingAmounts[0].currency)
    }

    @Test
    fun `paid items and cancelled orders don't count toward pending amount`() {
        val principal = user()
        every { meishiAdminClient.listUsers(any()) } returns listOf(profileFor(principal.id))

        val order = orderService.create(
            principal,
            CreateOrderRequest(
                items = listOf(CreateOrderItemRequest(title = "X", condition = Condition.NEW, currency = Currency.JPY, maxPrice = BigDecimal("2000"))),
            ),
        )
        orderService.adminMarkItemPaid(order.id, order.items[0].id, true)

        val cancelled = orderService.create(
            principal,
            CreateOrderRequest(
                items = listOf(CreateOrderItemRequest(title = "Y", condition = Condition.NEW, currency = Currency.JPY, maxPrice = BigDecimal("9999"))),
            ),
        )
        orderService.cancelOwn(cancelled.id, principal)

        val entry = dashboardService.userDashboard("Bearer irrelevant").single()

        assertTrue(entry.pendingAmounts.isEmpty())
        assertEquals(2, entry.orders.size)
    }

    @Test
    fun `an already-bought item still counts as pending even if an admin force-cancels the order afterward`() {
        exchangeRateService.setRate(BigDecimal("1.0000")) // 1:1 - this test is about cancellation, not conversion.
        val principal = user()
        every { meishiAdminClient.listUsers(any()) } returns listOf(profileFor(principal.id))

        val order = orderService.create(
            principal,
            CreateOrderRequest(
                items = listOf(CreateOrderItemRequest(title = "X", condition = Condition.NEW, currency = Currency.JPY)),
            ),
        )
        orderService.updateItem(order.id, order.items[0].id, OrderItemStatus.BOUGHT, BigDecimal("1200"))
        // Admin-only path, unlike cancelOwn, has no guard against cancelling an order with
        // already-bought items - the money spent on them must still show up as owed.
        orderService.updateStatus(order.id, com.omiyage.backend.order.OrderStatus.CANCELLED)

        val entry = dashboardService.userDashboard("Bearer irrelevant").single()

        assertEquals(1, entry.pendingAmounts.size)
        assertEquals(BigDecimal("1200.00"), entry.pendingAmounts[0].amount)
    }

    @Test
    fun `an unactivated user with no nickname yet is excluded instead of breaking the whole dashboard`() {
        // Regression test for the real bug behind "admin dashboard shows no users": MeishiApi
        // returns nickname=null for any not-yet-activated account, which used to fail to
        // deserialize into a non-nullable String and 500 the whole endpoint the moment one such
        // account existed. nickname is now nullable, and this filters the account out entirely.
        val principal = user()
        val unactivated = UserProfileDto(
            id = UUID.randomUUID(),
            nickname = null,
            email = null,
            phone = null,
            firstName = null,
            lastName = null,
            addressLine1 = null,
            addressLine2 = null,
            postalCode = null,
            city = null,
            country = null,
            activated = false,
        )
        every { meishiAdminClient.listUsers(any()) } returns listOf(profileFor(principal.id), unactivated)

        orderService.create(
            principal,
            CreateOrderRequest(items = listOf(CreateOrderItemRequest(title = "X", condition = Condition.NEW, currency = Currency.JPY))),
        )

        val entries = dashboardService.userDashboard("Bearer irrelevant")

        assertEquals(1, entries.size)
        assertEquals(principal.id, entries[0].user.id)
    }
}
