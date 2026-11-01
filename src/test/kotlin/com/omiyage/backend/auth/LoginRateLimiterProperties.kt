package com.omiyage.backend.auth

import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.Combinators
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.assertj.core.api.Assertions.assertThat
import org.springframework.web.server.ResponseStatusException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

private const val MAX_PER_IP = 20
private const val MAX_PER_NICKNAME = 10
private val WINDOW = Duration.ofMinutes(1)

class LoginRateLimiterProperties {

    private class MutableClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = now
    }

    data class Attempt(val ip: String, val nickname: String, val advanceMillis: Long)

    /** The same fixed-window rules written as plainly as possible. */
    private class Model(private val clock: MutableClock) {
        private class Window(val start: Instant, var attempts: Int)

        private val windows = HashMap<String, Window>()

        fun allows(ip: String, nickname: String): Boolean =
            acquire("ip:$ip", MAX_PER_IP) && acquire("nickname:${nickname.trim().lowercase()}", MAX_PER_NICKNAME)

        private fun acquire(key: String, limit: Int): Boolean {
            val now = clock.now
            val current = windows[key]
            val window = if (current != null && current.start.plus(WINDOW) > now) current else Window(now, 0)
            window.attempts++
            windows[key] = window
            return window.attempts <= limit
        }
    }

    @Provide
    fun attempts(): Arbitrary<List<Attempt>> {
        val ips = Arbitraries.of("1.1.1.1", "2.2.2.2", "3.3.3.3")
        val nicknames = Arbitraries.of("bob", "Bob", " bob ", "BOB", "alice", " Alice")
        val advances = Arbitraries.longs().between(0, 40_000)
        return Combinators.combine(ips, nicknames, advances).`as` { ip, nickname, advance -> Attempt(ip, nickname, advance) }
            .list().ofMaxSize(300)
    }

    private fun LoginRateLimiter.accepts(ip: String, nickname: String): Boolean =
        try {
            checkAttempt(ip, nickname)
            true
        } catch (_: ResponseStatusException) {
            false
        }

    @Property(tries = 1000)
    fun `behaves like the plain fixed-window model`(
        @ForAll("attempts") sequence: List<@JvmSuppressWildcards Attempt>,
    ) {
        val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
        val limiter = LoginRateLimiter(clock)
        val model = Model(clock)

        for (attempt in sequence) {
            clock.now = clock.now.plusMillis(attempt.advanceMillis)
            assertThat(limiter.accepts(attempt.ip, attempt.nickname)).isEqualTo(model.allows(attempt.ip, attempt.nickname))
        }
    }

    @Property(tries = 1000)
    fun `never accepts more than the per-ip and per-nickname caps inside one window`(
        @ForAll("attempts") sequence: List<@JvmSuppressWildcards Attempt>,
    ) {
        val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
        val limiter = LoginRateLimiter(clock)

        val accepted = sequence.map { attempt ->
            clock.now = Instant.parse("2026-01-01T00:00:00Z")
            attempt to limiter.accepts(attempt.ip, attempt.nickname)
        }.filter { it.second }.map { it.first }

        assertThat(accepted.groupingBy { it.ip }.eachCount().values).allMatch { it <= MAX_PER_IP }
        assertThat(accepted.groupingBy { it.nickname.trim().lowercase() }.eachCount().values).allMatch { it <= MAX_PER_NICKNAME }
    }

    @Property
    fun `padded and differently cased nicknames share one bucket`() {
        val limiter = LoginRateLimiter(MutableClock(Instant.parse("2026-01-01T00:00:00Z")))
        val variants = listOf("bob", " Bob ", "BOB", "bOb  ")

        repeat(MAX_PER_NICKNAME) { assertThat(limiter.accepts("10.0.0.$it", variants[it % variants.size])).isTrue() }

        variants.forEach { assertThat(limiter.accepts("10.1.1.1", it)).isFalse() }
    }

    @Property
    fun `every key resets once the window has passed`(@ForAll("attempts") sequence: List<@JvmSuppressWildcards Attempt>) {
        val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
        val limiter = LoginRateLimiter(clock)
        sequence.forEach { limiter.accepts(it.ip, it.nickname) }

        clock.now = clock.now.plus(WINDOW)

        assertThat(limiter.accepts("1.1.1.1", "bob")).isTrue()
    }
}
