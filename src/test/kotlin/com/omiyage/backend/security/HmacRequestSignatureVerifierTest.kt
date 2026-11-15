package com.omiyage.backend.security

import arrow.core.Either
import com.omiyage.backend.errors.BotAuthError
import com.omiyage.backend.order.OrderWebhookProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

private const val SECRET = "test-secret"
private const val NOW = 1_762_142_400L

class HmacRequestSignatureVerifierTest {

    private val clock = Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC)
    private val verifier = HmacRequestSignatureVerifier(OrderWebhookProperties("", SECRET), clock)

    private fun request(
        timestamp: Long = NOW,
        method: String = "GET",
        path: String = "/api/bot/orders",
        query: String = "page=0&size=25",
        signedWith: String = SECRET,
        timestampHeader: String? = timestamp.toString(),
        signatureHeader: String? = signRequest(signedWith, timestamp, "GET", "/api/bot/orders", "page=0&size=25"),
    ) = SignedRequest(timestampHeader, signatureHeader, method, path, query)

    @Test
    fun `the signature is pinned to a fixed test vector`() {
        assertThat(signRequest(SECRET, NOW, "GET", "/api/bot/orders", "page=0&size=25"))
            .isEqualTo("sha256=8d98a7a02ed36118b2847af0cda7d9240d1eedc7a90cecf3488518ed78593c84")
    }

    @Test
    fun `accepts a correctly signed request`() {
        assertThat(verifier.verify(request())).isEqualTo(Either.Right(Unit))
    }

    @Test
    fun `accepts a request with no query string`() {
        val signed = signRequest(SECRET, NOW, "GET", "/api/bot/orders", "")

        assertThat(verifier.verify(SignedRequest(NOW.toString(), signed, "GET", "/api/bot/orders", ""))).isEqualTo(Either.Right(Unit))
    }

    @Test
    fun `rejects a signature made with another secret`() {
        assertThat(verifier.verify(request(signedWith = "other-secret"))).isEqualTo(Either.Left(BotAuthError.BadSignature))
    }

    @Test
    fun `rejects a signature that is not the sha256 form`() {
        assertThat(verifier.verify(request(signatureHeader = "garbage"))).isEqualTo(Either.Left(BotAuthError.BadSignature))
    }

    @Test
    fun `rejects a signature reused on another path, query or method`() {
        assertThat(verifier.verify(request(path = "/api/bot/other"))).isEqualTo(Either.Left(BotAuthError.BadSignature))
        assertThat(verifier.verify(request(query = "page=1&size=25"))).isEqualTo(Either.Left(BotAuthError.BadSignature))
        assertThat(verifier.verify(request(method = "POST"))).isEqualTo(Either.Left(BotAuthError.BadSignature))
    }

    @Test
    fun `rejects a signature reused with another timestamp`() {
        assertThat(verifier.verify(request(timestampHeader = (NOW - 1).toString()))).isEqualTo(Either.Left(BotAuthError.BadSignature))
    }

    @Test
    fun `accepts a timestamp exactly 10 minutes old and rejects one second older`() {
        assertThat(verifier.verify(request(timestamp = NOW - 600))).isEqualTo(Either.Right(Unit))
        assertThat(verifier.verify(request(timestamp = NOW - 601))).isEqualTo(Either.Left(BotAuthError.StaleTimestamp))
    }

    @Test
    fun `accepts a timestamp exactly 1 minute ahead and rejects one second further`() {
        assertThat(verifier.verify(request(timestamp = NOW + 60))).isEqualTo(Either.Right(Unit))
        assertThat(verifier.verify(request(timestamp = NOW + 61))).isEqualTo(Either.Left(BotAuthError.FutureTimestamp))
    }

    @Test
    fun `rejects a missing header`() {
        assertThat(verifier.verify(request(timestampHeader = null))).isEqualTo(Either.Left(BotAuthError.MissingHeaders))
        assertThat(verifier.verify(request(signatureHeader = null))).isEqualTo(Either.Left(BotAuthError.MissingHeaders))
    }

    @Test
    fun `rejects a timestamp that is not a number`() {
        assertThat(verifier.verify(request(timestampHeader = "yesterday"))).isEqualTo(Either.Left(BotAuthError.MalformedTimestamp))
    }

    @Test
    fun `rejects everything while no secret is configured`() {
        val unconfigured = HmacRequestSignatureVerifier(OrderWebhookProperties(), clock)

        assertThat(unconfigured.verify(request(signedWith = "any-secret"))).isEqualTo(Either.Left(BotAuthError.NotConfigured))
    }
}
