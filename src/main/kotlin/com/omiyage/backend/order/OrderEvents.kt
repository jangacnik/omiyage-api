package com.omiyage.backend.order

import com.omiyage.backend.security.MeishiPrincipal
import com.omiyage.backend.security.Role
import org.springframework.context.ApplicationEventPublisher
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.util.UUID

const val ORDER_CREATED = "order.created"
const val ORDER_STATUS_CHANGED = "order.status_changed"

/** Webhook payload, the contract with omiyage-bot. See docs/order-events.md before changing it. */
data class OrderEvent(
    val eventId: UUID,
    val type: String,
    val occurredAt: String,
    val order: OrderEventOrder,
)

data class OrderEventOrder(
    val id: UUID,
    val reference: String,
    val userId: UUID,
    val discordName: String?,
    val nickname: String?,
    val status: OrderStatus,
    val previousStatus: OrderStatus?,
    val items: List<OrderEventItem>,
    val itemsTotalEur: String,
    val itemsTotalIsEstimate: Boolean,
)

data class OrderEventItem(val name: String, val quantity: Int)

/** Who caused the event, so the webhook can look up the owner's discordName with the caller's own
 *  token. Not a data class: toString must never print the token. */
class EventActor(val userId: UUID, val admin: Boolean, val authHeader: String)

/** [signedAt] is the X-Omiyage-Timestamp of every delivery attempt, fixed when the event is created. */
class PendingOrderEvent(val event: OrderEvent, val actor: EventActor?, val signedAt: Long)

/** Same short id the frontend shows for an order without a nickname. */
fun orderReference(orderId: UUID): String = orderId.toString().take(8)

internal fun Order.toEvent(eventId: UUID, type: String, occurredAt: String, previousStatus: OrderStatus?): OrderEvent {
    val orderId = requireNotNull(this.id)
    // Same rule as the frontend's order total: soldPrice, else the member's max price, both EUR.
    val total = items.fold(BigDecimal.ZERO) { sum, item ->
        sum + (item.soldPrice ?: item.maxPrice ?: BigDecimal.ZERO) * BigDecimal(item.quantity)
    }
    return OrderEvent(
        eventId = eventId,
        type = type,
        occurredAt = occurredAt,
        order = OrderEventOrder(
            id = orderId,
            reference = orderReference(orderId),
            userId = userId,
            discordName = null,
            nickname = nickname,
            status = status,
            previousStatus = previousStatus,
            items = items.map { OrderEventItem(it.inventoryItem?.title ?: requireNotNull(it.customTitle), it.quantity) },
            itemsTotalEur = total.setScale(2, RoundingMode.HALF_UP).toPlainString(),
            itemsTotalIsEstimate = items.any { it.soldPrice == null },
        ),
    )
}

/** Called inside the order transaction; OrderWebhook sends the event only after it commits. */
@Component
class OrderEventPublisher(
    private val publisher: ApplicationEventPublisher,
    private val properties: OrderWebhookProperties,
    private val clock: Clock = Clock.systemUTC(),
) {

    fun orderCreated(order: Order) = publish(order, ORDER_CREATED, previousStatus = null)

    fun statusChanged(order: Order, previousStatus: OrderStatus) {
        if (order.status != previousStatus) {
            publish(order, ORDER_STATUS_CHANGED, previousStatus)
        }
    }

    private fun publish(order: Order, type: String, previousStatus: OrderStatus?) {
        if (!properties.enabled) return
        val now = clock.instant()
        val event = order.toEvent(UUID.randomUUID(), type, now.toString(), previousStatus)
        publisher.publishEvent(PendingOrderEvent(event, currentActor(), now.epochSecond))
    }

    private fun currentActor(): EventActor? {
        val principal = SecurityContextHolder.getContext().authentication?.principal as? MeishiPrincipal ?: return null
        val request = (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.request ?: return null
        val authHeader = request.getHeader("Authorization") ?: return null
        return EventActor(principal.id, principal.role == Role.ADMIN, authHeader)
    }
}
