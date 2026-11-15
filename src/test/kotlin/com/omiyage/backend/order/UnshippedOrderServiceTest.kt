package com.omiyage.backend.order

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import java.util.UUID

class UnshippedOrderServiceTest {

    private val orders = mockk<OrderRepository>()
    private val service = UnshippedOrderService(orders)
    private val pageable = slot<Pageable>()
    private val excluded = slot<Collection<OrderStatus>>()

    private fun stubRepository(found: List<Order> = emptyList()) {
        every { orders.findFiltered(null, capture(excluded), capture(pageable)) } answers {
            PageImpl(found, pageable.captured, found.size.toLong())
        }
    }

    @Test
    fun `leaves out exactly the statuses from SENT onwards`() {
        stubRepository()

        service.list(0, 25)

        val unshipped = OrderStatus.entries.filterNot { it in excluded.captured }
        assertThat(unshipped).containsExactly(
            OrderStatus.REQUESTED,
            OrderStatus.IN_PROGRESS,
            OrderStatus.PURCHASED,
            OrderStatus.PREPARING_SHIPPING,
        )
    }

    @Test
    fun `lists the oldest order first, with the id as tiebreak`() {
        stubRepository()

        service.list(0, 25)

        assertThat(pageable.captured.sort).isEqualTo(Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("id")))
    }

    @Test
    fun `caps the page size and floors the page number`() {
        stubRepository()

        service.list(-3, 10_000)

        assertThat(pageable.captured.pageSize).isEqualTo(MAX_BOT_PAGE_SIZE)
        assertThat(pageable.captured.pageNumber).isEqualTo(0)
    }

    @Test
    fun `a size below one is raised to one`() {
        stubRepository()

        service.list(0, 0)

        assertThat(pageable.captured.pageSize).isEqualTo(1)
    }

    @Test
    fun `maps an order to the minimal bot fields`() {
        val order = Order(id = UUID.fromString("1a2b3c4d-0000-0000-0000-000000000000"), userId = UUID.randomUUID(), status = OrderStatus.PURCHASED, nickname = "Gifts")
        stubRepository(listOf(order))

        val page = service.list(0, 25)

        assertThat(page.totalElements).isEqualTo(1)
        assertThat(page.content.single()).isEqualTo(BotOrderDto(order.id!!, "1a2b3c4d", OrderStatus.PURCHASED, "Gifts", 0, order.createdAt))
    }
}
