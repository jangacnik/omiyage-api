package com.omiyage.backend.auth

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset


class LoginRateLimiterTest {

    private class MutableClock(var now: Instant) : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId) = this
        override fun instant() = now
    }

    private val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
    private val limiter = LoginRateLimiter(clock)

    @Test
    fun `blocks the eleventh attempt for one nickname within a minute`() {
        repeat(10) { assertDoesNotThrow { limiter.checkAttempt("1.1.1.${it}", "alice") } }

        val e = assertThrows<ResponseStatusException> { limiter.checkAttempt("9.9.9.9", "Alice") }
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, e.statusCode)
    }

    @Test
    fun `blocks the twenty-first attempt from one ip across nicknames`() {
        repeat(20) { assertDoesNotThrow { limiter.checkAttempt("1.1.1.1", "user$it") } }

        val e = assertThrows<ResponseStatusException> { limiter.checkAttempt("1.1.1.1", "another") }
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, e.statusCode)
    }

    @Test
    fun `allows attempts again after the window passes`() {
        repeat(10) { limiter.checkAttempt("1.1.1.1", "alice") }
        assertThrows<ResponseStatusException> { limiter.checkAttempt("1.1.1.1", "alice") }

        clock.now = clock.now.plus(Duration.ofMinutes(2))

        assertDoesNotThrow { limiter.checkAttempt("1.1.1.1", "alice") }
    }
}
