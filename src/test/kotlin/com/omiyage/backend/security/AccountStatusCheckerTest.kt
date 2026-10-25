package com.omiyage.backend.security

import com.omiyage.backend.auth.MeishiAuthClient
import com.omiyage.backend.auth.MeishiUserResponse
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.server.ResponseStatusException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

class AccountStatusCheckerTest {

    private class MutableClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId) = this
        override fun instant() = now
    }

    private val meishi = mockk<MeishiAuthClient>()
    private val clock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))
    private val checker = AccountStatusChecker(meishi, clock)
    private val user = MeishiPrincipal(UUID.randomUUID(), "bob", Role.USER)
    private val admin = MeishiPrincipal(UUID.randomUUID(), "root", Role.ADMIN)

    private fun me(status: String = "ACTIVE", role: String = "USER") = MeishiUserResponse(UUID.randomUUID().toString(), "bob", role, status)

    @Test
    fun `denies a frozen account`() {
        every { meishi.getMe("t") } returns me(status = "FROZEN")
        assertFalse(checker.isAllowed("t", user))
    }

    @Test
    fun `denies a demoted admin`() {
        every { meishi.getMe("t") } returns me(role = "USER")
        assertFalse(checker.isAllowed("t", admin))
    }

    @Test
    fun `allows an active account and caches the answer for the ttl`() {
        every { meishi.getMe("t") } returns me()
        assertTrue(checker.isAllowed("t", user))
        assertTrue(checker.isAllowed("t", user))
        verify(exactly = 1) { meishi.getMe("t") }

        clock.now = clock.now.plusSeconds(61)
        assertTrue(checker.isAllowed("t", user))
        verify(exactly = 2) { meishi.getMe("t") }
    }

    @Test
    fun `denies a token meishi itself rejects`() {
        every { meishi.getMe("t") } throws ResponseStatusException(HttpStatus.UNAUTHORIZED)
        assertFalse(checker.isAllowed("t", user))
    }

    @Test
    fun `fails open when meishi is unreachable`() {
        every { meishi.getMe("t") } throws ResourceAccessException("down")
        assertTrue(checker.isAllowed("t", user))
    }

    @Test
    fun `a cached answer for a user token does not let a stale admin token through`() {
        val sameUser = UUID.randomUUID()
        every { meishi.getMe(any()) } returns me(role = "USER")

        assertTrue(checker.isAllowed("user-token", MeishiPrincipal(sameUser, "bob", Role.USER)))
        assertFalse(checker.isAllowed("admin-token", MeishiPrincipal(sameUser, "bob", Role.ADMIN)))
    }

    @Test
    fun `allows an admin whose role is confirmed by meishi`() {
        every { meishi.getMe("t") } returns me(role = "ADMIN")
        assertTrue(checker.isAllowed("t", admin))
    }
}
