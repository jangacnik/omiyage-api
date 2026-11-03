package com.omiyage.backend.order

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.omiyage.backend.logging.LogCapture
import com.omiyage.backend.user.MeishiAdminClient
import com.omiyage.backend.user.MeishiProfileClient
import com.omiyage.backend.user.UserProfileDto
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.util.UUID

private const val SECRET = "test-secret"

class OrderWebhookTest {

    private val receiver = WebhookReceiver()
    private val profiles = mockk<MeishiProfileClient>()
    private val admin = mockk<MeishiAdminClient>()
    private val objectMapper = jacksonObjectMapper()
    private val owner = UUID.randomUUID()

    private fun webhook(retryDelays: List<Duration> = listOf(Duration.ofMillis(10), Duration.ofMillis(10))) =
        OrderWebhook(OrderWebhookProperties(receiver.url, SECRET, retryDelays), profiles, admin, objectMapper)

    private fun event() = OrderEvent(
        eventId = UUID.randomUUID(),
        type = ORDER_CREATED,
        occurredAt = "2026-11-03T10:00:00Z",
        order = OrderEventOrder(
            id = UUID.fromString("1a2b3c4d-0000-0000-0000-000000000000"),
            reference = "1a2b3c4d",
            userId = owner,
            discordName = null,
            nickname = null,
            status = OrderStatus.REQUESTED,
            previousStatus = null,
            items = listOf(OrderEventItem("Frieren card", 2)),
            itemsTotalEur = "10.00",
            itemsTotalIsEstimate = false,
        ),
    )

    private fun pending(actor: EventActor? = null, signedAt: Long = Instant.now().epochSecond) =
        PendingOrderEvent(event(), actor, signedAt)

    private fun profile(id: UUID, discordName: String?) =
        UserProfileDto(id, "bob", null, null, null, null, null, null, null, null, null, discordName = discordName)

    private fun awaitLog(capture: LogCapture, prefix: String) {
        val deadline = Instant.now().plusSeconds(10)
        while (capture.events.none { it.formattedMessage.startsWith(prefix) } && Instant.now() < deadline) {
            Thread.sleep(20)
        }
    }

    private fun ReceivedWebhook.json() = objectMapper.readTree(body)

    @AfterEach
    fun tearDown() = receiver.close()

    @Test
    fun `the signature is HMAC-SHA256 over timestamp, dot and body, matching the documented test vector`() {
        assertThat(signWebhook(SECRET, 1762142400, """{"id":"1"}""".toByteArray()))
            .isEqualTo("sha256=cf765ab67ec2fd104f0b5a7a2c97723d624c6cd12829e6ba100e3f05076f4129")
    }

    @Test
    fun `delivers a signed event with the event id inside the signed body`() {
        val event = event()
        val signedAt = Instant.now().epochSecond - 60

        webhook().onOrderEvent(PendingOrderEvent(event, actor = null, signedAt = signedAt))

        val received = receiver.next()
        val timestamp = requireNotNull(received.timestamp).toLong()
        assertThat(timestamp).isEqualTo(signedAt)
        assertThat(received.signature).isEqualTo(signWebhook(SECRET, timestamp, received.body))
        assertThat(received.json()["eventId"].asText()).isEqualTo(event.eventId.toString())
        assertThat(received.json()["order"]["items"][0]["name"].asText()).isEqualTo("Frieren card")
        assertThat(received.json()["order"]["discordName"].isNull).isTrue()
    }

    @Test
    fun `retries a server error with the identical signed request until it succeeds`() {
        receiver.respondWith(500, 503)

        webhook(retryDelays = listOf(Duration.ofMillis(10), Duration.ofMillis(1100))).onOrderEvent(pending())

        val attempts = List(3) { receiver.next() }
        assertThat(attempts.map { Triple(String(it.body), it.timestamp, it.signature) }.distinct()).hasSize(1)
        assertThat(attempts[0].signature).isEqualTo(signWebhook(SECRET, attempts[0].timestamp!!.toLong(), attempts[0].body))
        assertThat(receiver.nothingWithin(300)).isTrue()
    }

    @Test
    fun `gives up once every retry is used`() {
        receiver.respondWith(500, 500, 500, 500)

        webhook().onOrderEvent(pending())

        repeat(3) { receiver.next() }
        assertThat(receiver.nothingWithin(300)).isTrue()
    }

    @Test
    fun `an event too old to finish within five minutes of its timestamp is dropped unsent`() {
        LogCapture(OrderWebhook::class.java.name).use { capture ->
            val webhook = webhook()
            webhook.onOrderEvent(pending(signedAt = Instant.now().epochSecond - 294))
            webhook.stop()

            assertThat(capture.events.map { it.formattedMessage }.single()).startsWith("order_event_expired")
        }
        assertThat(receiver.nothingWithin(100)).isTrue()
    }

    @Test
    fun `does not retry a client error such as a rejected signature`() {
        receiver.respondWith(401)

        webhook().onOrderEvent(pending())

        receiver.next()
        assertThat(receiver.nothingWithin(300)).isTrue()
    }

    @Test
    fun `retries when the receiver cannot be reached, then gives up`() {
        val unreachable = OrderWebhook(
            OrderWebhookProperties("http://127.0.0.1:1/events", SECRET, listOf(Duration.ofMillis(10))),
            profiles,
            admin,
            objectMapper,
        )

        LogCapture(OrderWebhook::class.java.name).use { capture ->
            unreachable.onOrderEvent(pending())
            awaitLog(capture, "order_event_gave_up")

            val messages = capture.events.map { it.formattedMessage }
            assertThat(messages.filter { it.startsWith("order_event_attempt_failed") }).hasSize(2)
            assertThat(messages.single { it.startsWith("order_event_gave_up") }).contains("attempts=2")
        }
    }

    @Test
    fun `logs no token, secret or discordName`() {
        every { profiles.getMe(any()) } returns profile(owner, "bob_42")
        receiver.respondWith(500)
        val webhook = webhook()

        LogCapture.root().use { capture ->
            webhook.onOrderEvent(pending(EventActor(owner, admin = false, authHeader = "Bearer user-token")))
            receiver.next()
            receiver.next()
            webhook.stop()

            assertThat(capture.text()).doesNotContain("user-token").doesNotContain(SECRET).doesNotContain("bob_42")
        }
    }

    @Test
    fun `stop drops retries that are still waiting`() {
        receiver.respondWith(500)
        val webhook = webhook(retryDelays = listOf(Duration.ofHours(1)))

        LogCapture(OrderWebhook::class.java.name).use { capture ->
            webhook.onOrderEvent(pending())
            receiver.next()
            awaitLog(capture, "order_event_retry_scheduled")
            webhook.stop()

            assertThat(capture.events.map { it.formattedMessage }).contains("order_events_dropped_on_shutdown count=1")
        }
        assertThat(receiver.nothingWithin(100)).isTrue()
    }

    @Test
    fun `looks up the owner's discordName with the owner's own token`() {
        every { profiles.getMe("Bearer user") } returns profile(owner, "bob_42")

        webhook().onOrderEvent(pending(EventActor(owner, admin = false, authHeader = "Bearer user")))

        assertThat(receiver.next().json()["order"]["discordName"].asText()).isEqualTo("bob_42")
    }

    @Test
    fun `looks up the owner's discordName through the admin user list when an admin acts`() {
        every { admin.listUsers("Bearer admin") } returns listOf(profile(UUID.randomUUID(), "other"), profile(owner, "bob_42"))

        webhook().onOrderEvent(pending(EventActor(UUID.randomUUID(), admin = true, authHeader = "Bearer admin")))

        assertThat(receiver.next().json()["order"]["discordName"].asText()).isEqualTo("bob_42")
    }

    @Test
    fun `another member acting leaves discordName null without calling MeishiApi`() {
        webhook().onOrderEvent(pending(EventActor(UUID.randomUUID(), admin = false, authHeader = "Bearer other")))

        assertThat(receiver.next().json()["order"]["discordName"].isNull).isTrue()
        verify(exactly = 0) { profiles.getMe(any()) }
        verify(exactly = 0) { admin.listUsers(any()) }
    }

    @Test
    fun `a failed lookup still delivers the event, without discordName`() {
        every { profiles.getMe(any()) } throws IllegalStateException("meishi down")

        webhook().onOrderEvent(pending(EventActor(owner, admin = false, authHeader = "Bearer user")))

        assertThat(receiver.next().json()["order"]["discordName"].isNull).isTrue()
    }

    @Test
    fun `the webhook is enabled only with both url and secret`() {
        assertThat(OrderWebhookProperties().enabled).isFalse()
        assertThat(OrderWebhookProperties(url = receiver.url).enabled).isFalse()
        assertThat(OrderWebhookProperties(secret = SECRET).enabled).isFalse()
        assertThat(OrderWebhookProperties(receiver.url, SECRET).enabled).isTrue()
    }
}
