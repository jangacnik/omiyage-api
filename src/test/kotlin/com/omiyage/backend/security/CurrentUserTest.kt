package com.omiyage.backend.security

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import java.util.UUID

class CurrentUserTest {

    @AfterEach
    fun clearContext() = SecurityContextHolder.clearContext()

    @Test
    fun `principal returns the authenticated MeishiPrincipal`() {
        val principal = MeishiPrincipal(UUID.randomUUID(), "bob", Role.USER)
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(principal, null, emptyList())

        assertEquals(principal, CurrentUser.principal())
    }
}
