package com.omiyage.backend.order

import com.fasterxml.jackson.databind.ObjectMapper
import com.omiyage.backend.user.MeishiAdminClient
import com.omiyage.backend.user.MeishiProfileClient
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

const val TIMESTAMP_HEADER = "X-Omiyage-Timestamp"
const val SIGNATURE_HEADER = "X-Omiyage-Signature"

// The bot accepts a timestamp up to 10 minutes old; every attempt must end within EVENT_LIFETIME of
// it. These delays plus 5 attempts of at most 7 s each end after 191 s, well inside that.
internal val DEFAULT_RETRY_DELAYS = listOf(Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofMinutes(2))
private val CONNECT_TIMEOUT = Duration.ofSeconds(2)
private val REQUEST_TIMEOUT = Duration.ofSeconds(5)
private val SHUTDOWN_GRACE = Duration.ofSeconds(5)
private const val MAX_PENDING_EVENTS = 1000
private val EVENT_LIFETIME = Duration.ofMinutes(5)

@ConfigurationProperties(prefix = "omiyage.order-webhook")
data class OrderWebhookProperties(
    val url: String = "",
    val secret: String = "",
    val retryDelays: List<Duration> = DEFAULT_RETRY_DELAYS,
) {
    val enabled: Boolean get() = url.isNotBlank() && secret.isNotBlank()
}

/** HMAC-SHA256 over "<timestamp>.<raw body>", hex encoded, as sent in X-Omiyage-Signature. */
fun signWebhook(secret: String, timestamp: Long, body: ByteArray): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
    mac.update("$timestamp.".toByteArray())
    return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body))
}

/** Signed once; every retry sends exactly these bytes and headers, so the event id and timestamp
 *  the bot checks never change between attempts. */
internal class SignedEvent(val eventId: UUID, val body: ByteArray, val timestamp: Long, val signature: String)

private fun isRetryable(status: Int) = status == 408 || status == 429 || status >= 500

/** Delivers order events after commit, retrying in memory with backoff. Events still pending at
 *  shutdown are lost; see docs/order-events.md for why that is acceptable. */
@Component
class OrderWebhook(
    private val properties: OrderWebhookProperties,
    private val profiles: MeishiProfileClient,
    private val admin: MeishiAdminClient,
    private val objectMapper: ObjectMapper,
) {

    private val log = LoggerFactory.getLogger(OrderWebhook::class.java)
    private val events = LoggerFactory.getLogger("events")
    private val http = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build()
    private val slots = Semaphore(MAX_PENDING_EVENTS)
    private val executor = ScheduledThreadPoolExecutor(1) { Thread(it, "order-webhook").apply { isDaemon = true } }

    init {
        if (properties.url.isNotBlank() && properties.secret.isBlank()) {
            log.warn("order webhook url is set but the secret is not, so no order events are sent")
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onOrderEvent(pending: PendingOrderEvent) {
        if (!slots.tryAcquire()) {
            log.warn("order_event_dropped eventId={} reason=too_many_pending", pending.event.eventId)
            return
        }
        executor.execute { deliver(pending) }
    }

    @PreDestroy
    fun stop() {
        executor.shutdown()
        if (!executor.awaitTermination(SHUTDOWN_GRACE.toMillis(), TimeUnit.MILLISECONDS)) {
            log.warn("order_events_dropped_on_shutdown count={}", executor.shutdownNow().size)
        }
    }

    private fun deliver(pending: PendingOrderEvent) {
        val order = pending.event.order
        val event = pending.event.copy(order = order.copy(discordName = discordName(order.userId, pending.actor)))
        val body = objectMapper.writeValueAsBytes(event)
        attempt(SignedEvent(event.eventId, body, pending.signedAt, signWebhook(properties.secret, pending.signedAt, body)), 0)
    }

    // Best effort: MeishiApi owns discordName and only answers with a user or admin token.
    private fun discordName(userId: UUID, actor: EventActor?): String? = try {
        when {
            actor == null -> null
            actor.userId == userId -> profiles.getMe(actor.authHeader).discordName
            actor.admin -> admin.listUsers(actor.authHeader).firstOrNull { it.id == userId }?.discordName
            else -> null
        }
    } catch (e: Exception) {
        log.warn("could not look up discordName for an order event: {}", e.message)
        null
    }

    private fun attempt(signed: SignedEvent, attempt: Int) {
        val eventId = signed.eventId
        val lastSafeStart = Instant.ofEpochSecond(signed.timestamp) + EVENT_LIFETIME - CONNECT_TIMEOUT - REQUEST_TIMEOUT
        if (Instant.now().isAfter(lastSafeStart)) {
            log.warn("order_event_expired eventId={} attempts={}", eventId, attempt)
            slots.release()
            return
        }
        val status = try {
            send(signed)
        } catch (e: Exception) {
            log.warn("order_event_attempt_failed eventId={} attempt={} error={}", eventId, attempt + 1, e.toString())
            null
        }
        when {
            status != null && status in 200..299 -> {
                events.info("order_event_delivered eventId={} attempts={}", eventId, attempt + 1)
                slots.release()
            }
            (status == null || isRetryable(status)) && attempt < properties.retryDelays.size ->
                executor.schedule({ attempt(signed, attempt + 1) }, properties.retryDelays[attempt].toMillis(), TimeUnit.MILLISECONDS)
            else -> {
                log.warn("order_event_gave_up eventId={} attempts={} status={}", eventId, attempt + 1, status)
                slots.release()
            }
        }
    }

    private fun send(signed: SignedEvent): Int {
        val request = HttpRequest.newBuilder(URI.create(properties.url))
            .timeout(REQUEST_TIMEOUT)
            .header("Content-Type", "application/json")
            .header(TIMESTAMP_HEADER, signed.timestamp.toString())
            .header(SIGNATURE_HEADER, signed.signature)
            .POST(HttpRequest.BodyPublishers.ofByteArray(signed.body))
            .build()
        return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
    }
}
