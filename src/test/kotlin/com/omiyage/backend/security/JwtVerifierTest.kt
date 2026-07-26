package com.omiyage.backend.security

import com.fasterxml.jackson.databind.ObjectMapper
import com.omiyage.backend.config.MeishiProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

class JwtVerifierTest {

    private val secret = "test-secret"
    private val verifier = JwtVerifier(MeishiProperties(jwtSecret = secret, apiUrl = "http://localhost:8080"), ObjectMapper())

    @Test
    fun `verifies a valid token and extracts claims`() {
        val id = UUID.randomUUID()
        val token = TestJwt.sign(secret, subject = id.toString(), nickname = "alice", role = "ADMIN")

        val principal = verifier.verify(token)

        assertEquals(id, principal.id)
        assertEquals("alice", principal.nickname)
        assertEquals(Role.ADMIN, principal.role)
    }

    @Test
    fun `rejects a token signed with a different secret`() {
        val token = TestJwt.sign("wrong-secret")
        assertThrows<JwtVerificationException> { verifier.verify(token) }
    }

    @Test
    fun `rejects an expired token`() {
        val token = TestJwt.sign(secret, expiresAtEpochSeconds = System.currentTimeMillis() / 1000 - 60)
        assertThrows<JwtVerificationException> { verifier.verify(token) }
    }

    @Test
    fun `rejects a malformed token`() {
        assertThrows<JwtVerificationException> { verifier.verify("not-a-jwt") }
    }

    @Test
    fun `rejects a token with a tampered payload`() {
        val token = TestJwt.sign(secret, role = "USER")
        val (header, payload, signature) = token.split(".")
        val tamperedPayload = payload.dropLast(1) + if (payload.last() == 'A') 'B' else 'A'
        assertThrows<JwtVerificationException> { verifier.verify("$header.$tamperedPayload.$signature") }
    }
}
