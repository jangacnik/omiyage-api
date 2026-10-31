package com.omiyage.backend.auth

import com.omiyage.backend.logging.LogCapture
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.web.server.ResponseStatusException

class AuthServiceTest {

    private val meishi = mockk<MeishiAuthClient>()
    private val service = AuthService(meishi)

    @Test
    fun `returns the token for an active account`() {
        every { meishi.login("bob", "hunter2") } returns "the-token"
        every { meishi.getMe("the-token") } returns MeishiUserResponse("id", "bob", "USER", "ACTIVE")

        assertEquals("the-token", service.login("bob", "hunter2", "1.2.3.4"))
    }

    @Test
    fun `rejects a frozen account even with the correct password`() {
        every { meishi.login("bob", "hunter2") } returns "the-token"
        every { meishi.getMe("the-token") } returns MeishiUserResponse("id", "bob", "USER", "FROZEN")

        val exception = assertThrows(ResponseStatusException::class.java) { service.login("bob", "hunter2", "1.2.3.4") }
        assertEquals(403, exception.statusCode.value())
    }

    @Test
    fun `propagates invalid-credentials failures from MeishiApi`() {
        every { meishi.login("bob", "wrong") } throws ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED)

        assertThrows(ResponseStatusException::class.java) { service.login("bob", "wrong", "1.2.3.4") }
    }

    @Test
    fun `failed login logs a nickname hash and client ip but never the nickname`() {
        every { meishi.login("CANARY_Nick", "wrong") } throws ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED)

        LogCapture("security").use { capture ->
            assertThrows(ResponseStatusException::class.java) { service.login("CANARY_Nick", "wrong", "9.9.9.9") }

            val line = capture.events.single().formattedMessage
            assertTrue(line.startsWith("login_failed nicknameHash=${nicknameHash("canary_nick")}"))
            assertTrue(line.contains("clientIp=9.9.9.9"))
            assertFalse(line.contains("CANARY", ignoreCase = true))
        }
    }

    @Test
    fun `frozen account logs account_status_blocked with the user id`() {
        every { meishi.login("bob", "hunter2") } returns "the-token"
        every { meishi.getMe("the-token") } returns MeishiUserResponse("user-7", "bob", "USER", "FROZEN")

        LogCapture("security").use { capture ->
            assertThrows(ResponseStatusException::class.java) { service.login("bob", "hunter2", "1.2.3.4") }

            assertEquals("account_status_blocked userId=user-7 status=FROZEN", capture.events.single().formattedMessage)
        }
    }

    @Test
    fun `nickname hash is eight lowercase hex chars and ignores case and padding`() {
        assertEquals(nicknameHash("Bob"), nicknameHash("  bob "))
        assertTrue(Regex("^[0-9a-f]{8}$").matches(nicknameHash("bob")))
    }
}
