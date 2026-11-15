package com.omiyage.backend.security

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import arrow.core.right
import com.omiyage.backend.errors.BotAuthError
import com.omiyage.backend.order.OrderWebhookProperties
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

// Same window the bot applies to webhook events: 10 minutes old to 1 minute ahead.
private val MAX_AGE = Duration.ofMinutes(10)
private val MAX_CLOCK_SKEW = Duration.ofMinutes(1)

/** What the signature covers. [path] and [query] are the raw request URI parts exactly as sent (no
 *  decoding), [query] is "" when there is none. A bot request is a read, so its body is always empty. */
class SignedRequest(
    val timestampHeader: String?,
    val signatureHeader: String?,
    val method: String,
    val path: String,
    val query: String,
)

fun interface RequestSignatureVerifier {
    fun verify(request: SignedRequest): Either<BotAuthError, Unit>
}

/**
 * HMAC-SHA256 over "<timestamp>\n<METHOD>\n<path>\n<query>\n" followed by the (empty) body, with the
 * order webhook secret as key, sent as "sha256=" + lowercase hex in X-Omiyage-Signature next to the
 * Unix-seconds X-Omiyage-Timestamp. Mirrors the order webhook, which signs "<timestamp>." + body.
 */
fun signRequest(secret: String, timestamp: Long, method: String, path: String, query: String): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
    return "sha256=" + HexFormat.of().formatHex(mac.doFinal("$timestamp\n$method\n$path\n$query\n".toByteArray()))
}

/** Reuses the order webhook secret, so there is no second secret to manage. */
@Component
class HmacRequestSignatureVerifier(
    private val properties: OrderWebhookProperties,
    private val clock: Clock = Clock.systemUTC(),
) : RequestSignatureVerifier {

    override fun verify(request: SignedRequest): Either<BotAuthError, Unit> = either {
        ensure(properties.secret.isNotBlank()) { BotAuthError.NotConfigured }
        val timestampHeader = ensureNotNull(request.timestampHeader) { BotAuthError.MissingHeaders }
        val signatureHeader = ensureNotNull(request.signatureHeader) { BotAuthError.MissingHeaders }
        val timestamp = ensureNotNull(timestampHeader.toLongOrNull()) { BotAuthError.MalformedTimestamp }
        val expected = signRequest(properties.secret, timestamp, request.method, request.path, request.query)
        ensure(MessageDigest.isEqual(expected.toByteArray(), signatureHeader.toByteArray())) { BotAuthError.BadSignature }
        checkWindow(timestamp).bind()
    }

    private fun checkWindow(timestamp: Long): Either<BotAuthError, Unit> {
        val signedAt = Instant.ofEpochSecond(timestamp)
        val now = clock.instant()
        return when {
            signedAt.isBefore(now - MAX_AGE) -> BotAuthError.StaleTimestamp.left()
            signedAt.isAfter(now + MAX_CLOCK_SKEW) -> BotAuthError.FutureTimestamp.left()
            else -> Unit.right()
        }
    }
}
