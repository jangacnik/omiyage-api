package com.omiyage.backend.order

import java.time.OffsetDateTime
import java.util.UUID

/** What the bot may learn about an order that has not shipped: no prices, no member identity. */
data class BotOrderDto(
    val id: UUID,
    val reference: String,
    val status: OrderStatus,
    val nickname: String?,
    val itemCount: Int,
    val createdAt: OffsetDateTime,
)

fun Order.toBotDto(): BotOrderDto {
    val orderId = requireNotNull(id)
    return BotOrderDto(orderId, orderReference(orderId), status, nickname, items.size, createdAt)
}
