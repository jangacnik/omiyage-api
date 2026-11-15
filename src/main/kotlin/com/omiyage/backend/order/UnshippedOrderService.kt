package com.omiyage.backend.order

import com.omiyage.backend.web.PageResponse
import com.omiyage.backend.web.toPageResponse
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

const val MAX_BOT_PAGE_SIZE = 50

// Unshipped is every status before SENT. The repository query takes the statuses to leave out.
private val SHIPPED_OR_CLOSED = setOf(OrderStatus.SENT, OrderStatus.DELIVERED, OrderStatus.CANCELLED)

@Service
class UnshippedOrderService(private val orders: OrderRepository) {

    /** Oldest first, so the order waiting longest is listed first. [size] is capped at [MAX_BOT_PAGE_SIZE]. */
    @Transactional(readOnly = true)
    fun list(page: Int, size: Int): PageResponse<BotOrderDto> {
        val sort = Sort.by(Sort.Direction.ASC, "createdAt").and(Sort.by("id"))
        val pageRequest = PageRequest.of(page.coerceAtLeast(0), size.coerceIn(1, MAX_BOT_PAGE_SIZE), sort)
        return orders.findFiltered(null, SHIPPED_OR_CLOSED, pageRequest).toPageResponse { it.toBotDto() }
    }
}
