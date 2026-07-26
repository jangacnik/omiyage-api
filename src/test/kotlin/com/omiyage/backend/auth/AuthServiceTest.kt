package com.omiyage.backend.auth

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.web.server.ResponseStatusException

class AuthServiceTest {

    private val meishi = mockk<MeishiAuthClient>()
    private val service = AuthService(meishi)

    @Test
    fun `returns the token for an active account`() {
        every { meishi.login("bob", "hunter2") } returns "the-token"
        every { meishi.getMe("the-token") } returns MeishiUserResponse("id", "bob", "USER", "ACTIVE")

        assertEquals("the-token", service.login("bob", "hunter2"))
    }

    @Test
    fun `rejects a frozen account even with the correct password`() {
        every { meishi.login("bob", "hunter2") } returns "the-token"
        every { meishi.getMe("the-token") } returns MeishiUserResponse("id", "bob", "USER", "FROZEN")

        val exception = assertThrows(ResponseStatusException::class.java) { service.login("bob", "hunter2") }
        assertEquals(403, exception.statusCode.value())
    }

    @Test
    fun `propagates invalid-credentials failures from MeishiApi`() {
        every { meishi.login("bob", "wrong") } throws ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED)

        assertThrows(ResponseStatusException::class.java) { service.login("bob", "wrong") }
    }
}
