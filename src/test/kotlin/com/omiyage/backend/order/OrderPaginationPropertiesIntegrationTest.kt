package com.omiyage.backend.order

import com.omiyage.backend.PostgresIntegrationTest
import com.omiyage.backend.common.Currency
import com.omiyage.backend.security.MeishiPrincipal
import com.omiyage.backend.security.Role
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Random
import java.util.UUID

private const val CASES = 15
private const val MAX_ORDERS = 40

/** Randomised paging check (#76 target 6). jqwik has no Spring integration here, so each case is
 *  drawn from a seeded Random and the seed is in every failure message. */
@SpringBootTest
@Transactional
class OrderPaginationPropertiesIntegrationTest : PostgresIntegrationTest() {

    @Autowired
    lateinit var orderService: OrderService

    @Autowired
    lateinit var orders: OrderRepository

    @Autowired
    lateinit var entityManager: EntityManager

    private val base = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)

    /** Orders with deliberately colliding timestamps and item counts. */
    private fun seedOrders(random: Random, userId: UUID): List<Order> {
        val distinctTimes = 1 + random.nextInt(4)
        val saved = List(random.nextInt(MAX_ORDERS + 1)) {
            val at = base.plusMinutes(random.nextInt(distinctTimes).toLong())
            val order = Order(userId = userId, status = OrderStatus.PURCHASED, createdAt = at, updatedAt = at)
            repeat(random.nextInt(3)) { order.items.add(OrderItem(order = order, customTitle = "item", currency = Currency.EUR)) }
            orders.save(order)
        }
        entityManager.flush()
        entityManager.clear()
        return saved
    }

    private fun <T> allPages(totalPages: Int, fetch: (page: Int) -> List<T>): List<T> =
        (0 until totalPages).flatMap { fetch(it) }

    @Test
    fun `listMine pages hold every order exactly once, newest first`() {
        repeat(CASES) { case ->
            val seed = System.nanoTime() + case
            val random = Random(seed)
            val owner = MeishiPrincipal(UUID.randomUUID(), "bob", Role.USER)
            val seeded = seedOrders(random, owner.id)
            val pageSize = 1 + random.nextInt(30)
            val request = { page: Int -> PageRequest.of(page, pageSize, Sort.by(Sort.Direction.DESC, "createdAt")) }
            val totalPages = orderService.listMine(owner, request(0)).totalPages

            val listed = allPages(totalPages) { orderService.listMine(owner, request(it)).content }

            assertThat(listed.map { it.id }).`as`("seed=$seed pageSize=$pageSize").containsExactlyInAnyOrderElementsOf(seeded.map { it.id })
            assertThat(listed.map { it.createdAt }).`as`("seed=$seed").isSortedAccordingTo(reverseOrder())
        }
    }

    @Test
    fun `adminList pages hold every order exactly once for every sort`() {
        repeat(CASES) { case ->
            val seed = System.nanoTime() + case
            val random = Random(seed)
            val seeded = seedOrders(random, UUID.randomUUID()).map { it.id }.toSet()
            val pageSize = 5 + random.nextInt(26)
            val sortBy = AdminOrderSortBy.entries[random.nextInt(AdminOrderSortBy.entries.size)]
            val direction = if (random.nextBoolean()) Sort.Direction.ASC else Sort.Direction.DESC
            val fetch = { page: Int ->
                orderService.adminList(OrderStatus.PURCHASED, emptySet(), sortBy, direction, PageRequest.of(page, pageSize), "Bearer none")
            }
            val totalPages = fetch(0).totalPages

            val listed = allPages(totalPages) { fetch(it).content.map { order -> order.id } }

            assertThat(listed).`as`("seed=$seed pageSize=$pageSize sort=$sortBy $direction").doesNotHaveDuplicates()
            assertThat(listed).`as`("seed=$seed pageSize=$pageSize sort=$sortBy $direction").containsAll(seeded)
        }
    }
}
