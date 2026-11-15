package com.omiyage.backend.order

import com.omiyage.backend.PostgresIntegrationTest
import com.omiyage.backend.common.Currency
import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

// Older than anything else in the database, so the oldest-first list starts with these orders.
private val FIRST = OffsetDateTime.of(2000, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)

/** The unshipped filter against real Postgres: statuses, soft delete, paging and the size cap. */
@SpringBootTest
@Transactional
class UnshippedOrderServiceIntegrationTest : PostgresIntegrationTest() {

    @Autowired
    lateinit var service: UnshippedOrderService

    @Autowired
    lateinit var orders: OrderRepository

    @Autowired
    lateinit var entityManager: EntityManager

    private val userId = UUID.randomUUID()

    private fun order(status: OrderStatus, minute: Long, itemCount: Int = 0, nickname: String? = null): UUID {
        val at = FIRST.plusMinutes(minute)
        val order = Order(userId = userId, status = status, nickname = nickname, createdAt = at, updatedAt = at)
        repeat(itemCount) { order.items.add(OrderItem(order = order, customTitle = "item", currency = Currency.EUR)) }
        return requireNotNull(orders.save(order).id)
    }

    private fun flushAndClear() {
        entityManager.flush()
        entityManager.clear()
    }

    @Test
    fun `lists exactly the orders before SENT, oldest first, and leaves out shipped, closed and deleted ones`() {
        val byStatus = OrderStatus.entries.mapIndexed { index, status -> status to order(status, index.toLong(), itemCount = index) }.toMap()
        val deleted = order(OrderStatus.PURCHASED, minute = 10)
        flushAndClear()
        orders.delete(orders.findById(deleted).get())
        flushAndClear()

        val listed = service.list(page = 0, size = 50).content.filter { it.id in byStatus.values || it.id == deleted }

        val unshipped = listOf(OrderStatus.REQUESTED, OrderStatus.IN_PROGRESS, OrderStatus.PURCHASED, OrderStatus.PREPARING_SHIPPING)
        assertThat(listed.map { it.id }).containsExactlyElementsOf(unshipped.map { byStatus.getValue(it) })
        assertThat(listed.map { it.status }).containsExactlyElementsOf(unshipped)
        assertThat(listed.map { it.itemCount }).containsExactly(0, 1, 2, 3)
    }

    @Test
    fun `maps the reference and nickname`() {
        val id = order(OrderStatus.REQUESTED, minute = 0, nickname = "Birthday presents")
        flushAndClear()

        val dto = service.list(page = 0, size = 50).content.single { it.id == id }

        assertThat(dto.reference).isEqualTo(id.toString().take(8))
        assertThat(dto.nickname).isEqualTo("Birthday presents")
    }

    @Test
    fun `pages through the orders in the same order`() {
        val ids = List(5) { order(OrderStatus.IN_PROGRESS, minute = it.toLong()) }
        flushAndClear()

        val pages = (0..2).map { service.list(page = it, size = 2) }

        assertThat(pages[0].content.map { it.id }).containsExactly(ids[0], ids[1])
        assertThat(pages[1].content.map { it.id }).containsExactly(ids[2], ids[3])
        assertThat(pages[2].content.first().id).isEqualTo(ids[4])
        assertThat(pages[0].hasNext).isTrue()
        assertThat(pages[0].totalElements).isGreaterThanOrEqualTo(5)
    }

    @Test
    fun `caps a page at 50 however many are asked for`() {
        repeat(MAX_BOT_PAGE_SIZE + 5) { order(OrderStatus.REQUESTED, minute = it.toLong()) }
        flushAndClear()

        val page = service.list(page = 0, size = 5000)

        assertThat(page.content).hasSize(MAX_BOT_PAGE_SIZE)
        assertThat(page.size).isEqualTo(MAX_BOT_PAGE_SIZE)
        assertThat(page.hasNext).isTrue()
    }
}
