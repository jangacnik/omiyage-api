package com.omiyage.backend.dashboard

import com.ninjasquad.springmockk.MockkBean
import com.omiyage.backend.PostgresIntegrationTest
import com.omiyage.backend.common.Condition
import com.omiyage.backend.common.Currency
import com.omiyage.backend.order.CreateOrderItemRequest
import com.omiyage.backend.order.CreateOrderRequest
import com.omiyage.backend.order.OrderService
import com.omiyage.backend.security.MeishiPrincipal
import com.omiyage.backend.security.Role
import com.omiyage.backend.user.MeishiAdminClient
import com.omiyage.backend.user.UserProfileDto
import io.mockk.every
import jakarta.persistence.EntityManager
import jakarta.persistence.EntityManagerFactory
import org.hibernate.SessionFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

private const val USERS = 20
private const val ORDERS_PER_USER = 5
private const val MAX_STATEMENTS = 10L

@SpringBootTest(properties = ["spring.jpa.properties.hibernate.generate_statistics=true"])
@Transactional
class DashboardQueryCountIntegrationTest : PostgresIntegrationTest() {

    @Autowired
    lateinit var dashboardService: DashboardService

    @Autowired
    lateinit var orderService: OrderService

    @Autowired
    lateinit var entityManager: EntityManager

    @Autowired
    lateinit var entityManagerFactory: EntityManagerFactory

    @MockkBean
    lateinit var meishiAdminClient: MeishiAdminClient

    private fun profileFor(userId: UUID) = UserProfileDto(
        id = userId, nickname = "u", email = null, phone = null, firstName = null, lastName = null,
        addressLine1 = null, addressLine2 = null, postalCode = null, city = null, country = null,
    )

    @Test
    fun `dashboard statement count does not grow with the number of users and orders`() {
        val principals = List(USERS) { MeishiPrincipal(UUID.randomUUID(), "user$it", Role.USER) }
        principals.forEach { principal ->
            repeat(ORDERS_PER_USER) {
                orderService.create(
                    principal,
                    CreateOrderRequest(items = listOf(CreateOrderItemRequest(title = "Item", condition = Condition.NEW, currency = Currency.JPY))),
                )
            }
        }
        every { meishiAdminClient.listUsers(any()) } returns principals.map { profileFor(it.id) }
        entityManager.flush()
        entityManager.clear()
        val statistics = entityManagerFactory.unwrap(SessionFactory::class.java).statistics
        statistics.clear()

        val entries = dashboardService.userDashboard("Bearer x")

        assertEquals(USERS, entries.size)
        assertEquals(USERS * ORDERS_PER_USER, entries.sumOf { it.orders.size })
        assertTrue(statistics.prepareStatementCount <= MAX_STATEMENTS, "statements: ${statistics.prepareStatementCount}")
    }
}
