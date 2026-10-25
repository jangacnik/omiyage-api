package com.omiyage.backend.security

import com.omiyage.backend.auth.MeishiAuthClient
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private val STATUS_TTL = Duration.ofSeconds(60)

/** Tokens carry no account status and cannot be revoked, so mutating and admin calls re-check the
 *  account against MeishiApi (cached briefly per user). Fails open when Meishi is unreachable so
 *  an outage there does not take every write down; a token Meishi itself rejects is denied. */
@Component
class AccountStatusChecker(private val meishi: MeishiAuthClient, private val clock: Clock = Clock.systemUTC()) {

    private data class Entry(val frozen: Boolean, val role: String?, val checkedAt: Instant)

    private val log = LoggerFactory.getLogger(AccountStatusChecker::class.java)
    private val cache = ConcurrentHashMap<UUID, Entry>()

    fun isAllowed(token: String, principal: MeishiPrincipal): Boolean {
        val entry = cachedEntry(principal.id) ?: fetch(token, principal.id) ?: return true
        return entry.isAllowedFor(principal)
    }

    private fun Entry.isAllowedFor(principal: MeishiPrincipal): Boolean =
        !frozen && (principal.role != Role.ADMIN || role == Role.ADMIN.name)

    private fun cachedEntry(id: UUID): Entry? {
        val entry = cache[id] ?: return null
        if (entry.checkedAt.plus(STATUS_TTL) > clock.instant()) return entry
        cache.remove(id, entry)
        return null
    }

    // Caches Meishi's facts rather than a verdict, so tokens carrying different role claims for
    // the same user are each judged against the same fresh state. A token Meishi itself rejects is
    // cached as frozen.
    private fun fetch(token: String, id: UUID): Entry? =
        try {
            val me = meishi.getMe(token)
            Entry(me.status == "FROZEN", me.role, clock.instant())
        } catch (e: ResponseStatusException) {
            Entry(frozen = true, role = null, checkedAt = clock.instant())
        } catch (e: Exception) {
            log.warn("account status check skipped, meishi unavailable: {}", e.javaClass.simpleName)
            null
        }?.also { cache[id] = it }
}
