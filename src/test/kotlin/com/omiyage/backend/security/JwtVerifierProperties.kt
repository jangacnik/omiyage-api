package com.omiyage.backend.security

import com.fasterxml.jackson.databind.ObjectMapper
import com.omiyage.backend.config.MeishiProperties
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.Assume
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import net.jqwik.api.constraints.IntRange
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private const val CURRENT_SECRET = "current-secret"
private const val PREVIOUS_SECRET = "previous-secret"
private const val DAY_SECONDS = 24L * 60 * 60

class JwtVerifierProperties {

    private val mapper = ObjectMapper()
    private val verifier = JwtVerifier(
        MeishiProperties(jwtSecret = CURRENT_SECRET, apiUrl = "http://localhost:8080", jwtSecretPrevious = PREVIOUS_SECRET),
        mapper,
    )

    private fun base64Url(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun signedWithPayload(claims: Map<String, Any?>, secret: String = CURRENT_SECRET): String {
        val header = base64Url(mapper.writeValueAsBytes(mapOf("alg" to "HS256", "typ" to "JWT")))
        val payload = base64Url(mapper.writeValueAsBytes(claims))
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256")) }
        return "$header.$payload.${base64Url(mac.doFinal("$header.$payload".toByteArray(Charsets.US_ASCII)))}"
    }

    private fun nowSeconds() = Instant.now().epochSecond

    @Provide
    fun looseJsonValues(): Arbitrary<Any?> = Arbitraries.oneOf<Any?>(
        Arbitraries.strings(),
        Arbitraries.longs(),
        Arbitraries.doubles(),
        Arbitraries.of<Any?>(true, false),
        Arbitraries.just<Any?>(null),
        Arbitraries.just<Any?>(UUID.randomUUID().toString()),
        Arbitraries.of<Any?>("USER", "ADMIN"),
    )

    @Property
    fun `tokens from the current or previous key round-trip including unicode nicknames`(
        @ForAll nickname: String,
        @ForAll("secrets") secret: String,
    ) {
        val id = UUID.randomUUID()
        val token = TestJwt.sign(secret, subject = id.toString(), nickname = nickname, role = "ADMIN")

        val principal = verifier.verify(token)

        assertThat(principal).isEqualTo(MeishiPrincipal(id, nickname, Role.ADMIN))
    }

    @Provide
    fun secrets(): Arbitrary<String> = Arbitraries.of(CURRENT_SECRET, PREVIOUS_SECRET)

    @Property
    fun `changing any single character of a valid token is rejected`(
        @ForAll @IntRange(min = 0, max = 10_000) position: Int,
        @ForAll("base64UrlChars") replacement: Char,
    ) {
        val token = TestJwt.sign(CURRENT_SECRET)
        // The final signature character carries unused bits, so some flips there decode to the same bytes.
        val index = position % (token.length - 1)
        Assume.that(token[index] != '.' && token[index] != replacement)
        val mutated = token.substring(0, index) + replacement + token.substring(index + 1)

        assertThatThrownBy { verifier.verify(mutated) }.isInstanceOf(JwtVerificationException::class.java)
    }

    @Provide
    fun base64UrlChars(): Arbitrary<Char> =
        Arbitraries.chars().range('A', 'Z').range('a', 'z').range('0', '9').with('-', '_')

    @Property
    fun `arbitrary strings only ever raise JwtVerificationException`(@ForAll text: String) {
        assertThatThrownBy { verifier.verify(text) }.isInstanceOf(JwtVerificationException::class.java)
    }

    @Property
    fun `arbitrary three part tokens only ever raise JwtVerificationException`(
        @ForAll header: String,
        @ForAll payload: String,
        @ForAll signature: String,
    ) {
        Assume.that(!header.contains('.') && !payload.contains('.') && !signature.contains('.'))

        assertThatThrownBy { verifier.verify("$header.$payload.$signature") }.isInstanceOf(JwtVerificationException::class.java)
    }

    @Property
    fun `correctly signed tokens with random claim types only ever raise JwtVerificationException or verify`(
        @ForAll("looseJsonValues") sub: Any?,
        @ForAll("looseJsonValues") nickname: Any?,
        @ForAll("looseJsonValues") role: Any?,
        @ForAll("looseJsonValues") exp: Any?,
        @ForAll("looseJsonValues") iat: Any?,
    ) {
        val token = signedWithPayload(mapOf("sub" to sub, "nickname" to nickname, "role" to role, "exp" to exp, "iat" to iat))

        try {
            verifier.verify(token)
        } catch (_: JwtVerificationException) {
        }
    }

    @Property
    fun `a token expiring at or before now is rejected`(@ForAll @IntRange(min = 0, max = 100_000) secondsAgo: Int) {
        val token = TestJwt.sign(CURRENT_SECRET, expiresAtEpochSeconds = nowSeconds() - secondsAgo)

        assertThatThrownBy { verifier.verify(token) }.isInstanceOf(JwtVerificationException::class.java)
    }

    @Property
    fun `lifetime above 24 hours is rejected and up to 24 hours is accepted`(
        @ForAll @IntRange(min = 1, max = 100) extra: Int,
    ) {
        val issuedAt = nowSeconds()

        assertThat(verifier.verify(TestJwt.sign(CURRENT_SECRET, issuedAtEpochSeconds = issuedAt, expiresAtEpochSeconds = issuedAt + DAY_SECONDS)))
            .isNotNull()
        assertThatThrownBy {
            verifier.verify(TestJwt.sign(CURRENT_SECRET, issuedAtEpochSeconds = issuedAt, expiresAtEpochSeconds = issuedAt + DAY_SECONDS + extra))
        }.isInstanceOf(JwtVerificationException::class.java)
    }

    @Property
    fun `any algorithm other than HS256 is rejected`(@ForAll("otherAlgorithms") algorithm: String) {
        assertThatThrownBy { verifier.verify(TestJwt.sign(CURRENT_SECRET, algorithm = algorithm)) }
            .isInstanceOf(JwtVerificationException::class.java)
    }

    @Provide
    fun otherAlgorithms(): Arbitrary<String> =
        Arbitraries.oneOf(Arbitraries.of("none", "None", "hs256", "HS384", "HS512", "RS256", "ES256", ""), Arbitraries.strings().filter { it != "HS256" })
}
