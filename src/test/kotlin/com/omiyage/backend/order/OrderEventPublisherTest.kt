package com.omiyage.backend.order

import com.omiyage.backend.security.MeishiPrincipal
import com.omiyage.backend.security.Role
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class OrderEventPublisherTest {

    private val springEvents = mockk<ApplicationEventPublisher>(relaxed = true)
    private val enabled = OrderWebhookProperties("http://bot/events", "secret")
    private val clock = Clock.fixed(Instant.parse("2026-11-03T10:00:00Z"), ZoneOffset.UTC)
    private val order = Order(id = UUID.randomUUID(), userId = UUID.randomUUID(), status = OrderStatus.IN_PROGRESS)

    @AfterEach
    fun tearDown() {
        SecurityContextHolder.clearContext()
        RequestContextHolder.resetRequestAttributes()
    }

    private fun publishedEvent(): PendingOrderEvent {
        val published = slot<Any>()
        verify { springEvents.publishEvent(capture(published)) }
        return published.captured as PendingOrderEvent
    }

    private fun signIn(principal: MeishiPrincipal, authHeader: String) {
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(principal, null, emptyList())
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(MockHttpServletRequest().apply { addHeader("Authorization", authHeader) }))
    }

    @Test
    fun `publishes nothing while the webhook is not configured`() {
        val publisher = OrderEventPublisher(springEvents, OrderWebhookProperties(), clock)

        publisher.orderCreated(order)
        publisher.statusChanged(order, OrderStatus.REQUESTED)

        verify(exactly = 0) { springEvents.publishEvent(any<Any>()) }
    }

    @Test
    fun `an unchanged status publishes nothing`() {
        OrderEventPublisher(springEvents, enabled, clock).statusChanged(order, OrderStatus.IN_PROGRESS)

        verify(exactly = 0) { springEvents.publishEvent(any<Any>()) }
    }

    @Test
    fun `a status change carries both statuses, the clock time and no actor outside a request`() {
        OrderEventPublisher(springEvents, enabled, clock).statusChanged(order, OrderStatus.REQUESTED)

        val pending = publishedEvent()
        assertThat(pending.event.type).isEqualTo(ORDER_STATUS_CHANGED)
        assertThat(pending.event.occurredAt).isEqualTo("2026-11-03T10:00:00Z")
        assertThat(pending.signedAt).isEqualTo(Instant.parse("2026-11-03T10:00:00Z").epochSecond)
        assertThat(pending.event.order.previousStatus).isEqualTo(OrderStatus.REQUESTED)
        assertThat(pending.event.order.status).isEqualTo(OrderStatus.IN_PROGRESS)
        assertThat(pending.event.order.itemsTotalEur).isEqualTo("0.00")
        assertThat(pending.event.order.itemsTotalIsEstimate).isFalse()
        assertThat(pending.actor).isNull()
    }

    @Test
    fun `the actor is taken from the signed-in request`() {
        val admin = MeishiPrincipal(UUID.randomUUID(), "root", Role.ADMIN)
        signIn(admin, "Bearer admin-token")

        OrderEventPublisher(springEvents, enabled, clock).orderCreated(order)

        val actor = requireNotNull(publishedEvent().actor)
        assertThat(actor.userId).isEqualTo(admin.id)
        assertThat(actor.admin).isTrue()
        assertThat(actor.authHeader).isEqualTo("Bearer admin-token")
        assertThat(actor.toString()).doesNotContain("admin-token")
    }

    @Test
    fun `a signed-in principal without a request has no actor`() {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(MeishiPrincipal(UUID.randomUUID(), "bob", Role.USER), null, emptyList())

        OrderEventPublisher(springEvents, enabled, clock).orderCreated(order)

        assertThat(publishedEvent().actor).isNull()
    }
}
