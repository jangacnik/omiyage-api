package com.omiyage.backend.auth

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

private const val MAX_ATTEMPTS_PER_IP = 20
private const val MAX_ATTEMPTS_PER_NICKNAME = 10
private const val CLEANUP_THRESHOLD = 10_000
private val WINDOW = Duration.ofMinutes(1)

/** Fixed-window, in-memory limiter: login is a public relay to MeishiApi, so cap attempts per
 *  client IP and per nickname. Per-replica state, which is enough for the single-replica deploy. */
@Component
class LoginRateLimiter(private val clock: Clock = Clock.systemUTC()) {

    private class Window(val start: Instant, var attempts: Int)

    private val windows = ConcurrentHashMap<String, Window>()

    @Volatile
    private var lastSweep = clock.instant()

    fun checkAttempt(ip: String, nickname: String) {
        val allowed = tryAcquire("ip:$ip", MAX_ATTEMPTS_PER_IP) &&
            tryAcquire("nickname:${nickname.trim().lowercase()}", MAX_ATTEMPTS_PER_NICKNAME)
        if (!allowed) throw ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "too many login attempts")
    }

    private fun sweepExpiredWindows(now: Instant) {
        if (windows.size <= CLEANUP_THRESHOLD || lastSweep.plus(WINDOW) > now) return
        lastSweep = now
        windows.values.removeIf { it.start.plus(WINDOW) <= now }
    }

    private fun tryAcquire(key: String, limit: Int): Boolean {
        val now = clock.instant()
        sweepExpiredWindows(now)
        val window = windows.compute(key) { _, current ->
            val active = current?.takeIf { it.start.plus(WINDOW) > now } ?: Window(now, 0)
            active.also { it.attempts++ }
        }!!
        return window.attempts <= limit
    }
}
