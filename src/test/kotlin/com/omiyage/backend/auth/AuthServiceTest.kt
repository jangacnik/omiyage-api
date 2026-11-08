package com.omiyage.backend.auth

import com.omiyage.backend.errors.statusCode
import com.omiyage.backend.errors.failureOf
import com.omiyage.backend.errors.orFail
import com.omiyage.backend.errors.AuthError
import arrow.core.left
import arrow.core.right
import com.omiyage.backend.logging.LogCapture
import com.omiyage.backend.security.JwtVerificationException
import com.omiyage.backend.security.JwtVerifier
import com.omiyage.backend.security.MeishiPrincipal
import com.omiyage.backend.security.Role
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

class AuthServiceTest {

    private val meishi = mockk<MeishiAuthClient>()
    private val verifier = mockk<JwtVerifier>()
    private val service = AuthService(meishi, verifier)
    private val principal = MeishiPrincipal(UUID.randomUUID(), "bob", Role.USER)

    @Test
    fun `returns the token for an active account`() {
        every { meishi.login("bob", "hunter2") } returns "the-token".right()
        every { meishi.getMe("the-token") } returns MeishiUserResponse("id", "bob", "USER", "ACTIVE").right()

        assertEquals("the-token", service.login("bob", "hunter2", "1.2.3.4").orFail())
    }

    @Test
    fun `rejects a frozen account even with the correct password`() {
        every { meishi.login("bob", "hunter2") } returns "the-token".right()
        every { meishi.getMe("the-token") } returns MeishiUserResponse("id", "bob", "USER", "FROZEN").right()

        val exception = failureOf { service.login("bob", "hunter2", "1.2.3.4") }
        assertEquals(403, exception.statusCode.value())
    }

    @Test
    fun `propagates invalid-credentials failures from MeishiApi`() {
        every { meishi.login("bob", "wrong") } returns AuthError.InvalidCredentials.left()

        failureOf { service.login("bob", "wrong", "1.2.3.4") }
    }

    @Test
    fun `failed login logs a nickname hash and client ip but never the nickname`() {
        every { meishi.login("CANARY_Nick", "wrong") } returns AuthError.InvalidCredentials.left()

        LogCapture("security").use { capture ->
            failureOf { service.login("CANARY_Nick", "wrong", "9.9.9.9") }

            val line = capture.events.single().formattedMessage
            assertTrue(line.startsWith("login_failed nicknameHash=${nicknameHash("canary_nick")}"))
            assertTrue(line.contains("clientIp=9.9.9.9"))
            assertFalse(line.contains("CANARY", ignoreCase = true))
        }
    }

    @Test
    fun `frozen account logs account_status_blocked with the user id`() {
        every { meishi.login("bob", "hunter2") } returns "the-token".right()
        every { meishi.getMe("the-token") } returns MeishiUserResponse("user-7", "bob", "USER", "FROZEN").right()

        LogCapture("security").use { capture ->
            failureOf { service.login("bob", "hunter2", "1.2.3.4") }

            assertEquals("account_status_blocked userId=user-7 status=FROZEN", capture.events.single().formattedMessage)
        }
    }

    @Test
    fun `a hosted-login code gives the token for an active account`() {
        every { meishi.exchangeCode("omiyage", "code", "verifier", "https://app/cb") } returns "sso-token".right()
        every { verifier.verify("sso-token") } returns principal
        every { meishi.getMe("sso-token") } returns MeishiUserResponse("id", "bob", "USER", "ACTIVE").right()

        assertEquals("sso-token", service.loginWithMeishiCode("omiyage", "code", "verifier", "https://app/cb", "1.2.3.4").orFail())
    }

    @Test
    fun `a hosted-login code for a frozen account is rejected`() {
        every { meishi.exchangeCode("omiyage", "code", "verifier", "https://app/cb") } returns "sso-token".right()
        every { verifier.verify("sso-token") } returns principal
        every { meishi.getMe("sso-token") } returns MeishiUserResponse("id", "bob", "USER", "FROZEN").right()

        val exception = failureOf {
            service.loginWithMeishiCode("omiyage", "code", "verifier", "https://app/cb", "1.2.3.4")
        }
        assertEquals(403, exception.statusCode.value())
    }

    @Test
    fun `a token omiyage-api would reject is not handed out`() {
        every { meishi.exchangeCode("omiyage", "code", "verifier", "https://app/cb") } returns "sso-token".right()
        every { verifier.verify("sso-token") } throws JwtVerificationException("bad signature")

        LogCapture("security").use { capture ->
            val exception = failureOf {
                service.loginWithMeishiCode("omiyage", "code", "verifier", "https://app/cb", "1.2.3.4")
            }

            assertEquals(502, exception.statusCode.value())
            assertEquals("sso_token_rejected reason=bad signature", capture.events.single().formattedMessage)
        }
        verify(exactly = 0) { meishi.getMe(any()) }
    }

    @Test
    fun `a failed code exchange is logged with the client ip and never the code`() {
        every { meishi.exchangeCode(any(), any(), any(), any()) } returns AuthError.InvalidCredentials.left()

        LogCapture("security").use { capture ->
            failureOf {
                service.loginWithMeishiCode("omiyage", "CANARY_CODE", "verifier", "https://app/cb", "9.9.9.9")
            }

            val line = capture.events.single().formattedMessage
            assertEquals("sso_exchange_failed status=401 clientIp=9.9.9.9", line)
        }
    }

    @Test
    fun `nickname hash is eight lowercase hex chars and ignores case and padding`() {
        assertEquals(nicknameHash("Bob"), nicknameHash("  bob "))
        assertTrue(Regex("^[0-9a-f]{8}$").matches(nicknameHash("bob")))
    }
}
