package com.omiyage.backend.auth

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.omiyage.backend.errors.AuthError
import com.omiyage.backend.errors.DomainError
import org.springframework.stereotype.Component
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

    fun checkAttempt(ip: String, nickname: String): Either<DomainError, Unit> {
        val allowed = tryAcquire("ip:$ip", MAX_ATTEMPTS_PER_IP) &&
            tryAcquire("nickname:${nickname.trim().lowercase()}", MAX_ATTEMPTS_PER_NICKNAME)
        return if (allowed) Unit.right() else AuthError.TooManyAttempts.left()
    }

    /** For logins that carry no nickname, such as a hosted-login code exchange. */
    fun checkAttempt(ip: String): Either<DomainError, Unit> =
        if (tryAcquire("ip:$ip", MAX_ATTEMPTS_PER_IP)) Unit.right() else AuthError.TooManyAttempts.left()

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
