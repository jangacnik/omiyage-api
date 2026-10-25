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
    fun `accepts tokens signed with either the current or the previous secret during rotation`() {
        val rotating = JwtVerifier(
            MeishiProperties(jwtSecret = "new-secret", apiUrl = "http://localhost:8080", jwtSecretPrevious = "old-secret"),
            ObjectMapper(),
        )

        assertEquals("bob", rotating.verify(TestJwt.sign("new-secret")).nickname)
        assertEquals("bob", rotating.verify(TestJwt.sign("old-secret")).nickname)
        assertThrows<JwtVerificationException> { rotating.verify(TestJwt.sign("third-secret")) }
    }

    @Test
    fun `rejects the previous secret once rotation is finished`() {
        assertThrows<JwtVerificationException> { verifier.verify(TestJwt.sign("old-secret")) }
    }

    @Test
    fun `rejects a token whose header algorithm is not HS256`() {
        assertThrows<JwtVerificationException> { verifier.verify(TestJwt.sign(secret, algorithm = "none")) }
        assertThrows<JwtVerificationException> { verifier.verify(TestJwt.sign(secret, algorithm = "HS512")) }
    }

    @Test
    fun `rejects a token signed with a different secret`() {
        val token = TestJwt.sign("wrong-secret")
        assertThrows<JwtVerificationException> { verifier.verify(token) }
    }

    @Test
    fun `rejects a token whose lifetime exceeds 24 hours`() {
        val now = System.currentTimeMillis() / 1000
        val token = TestJwt.sign(secret, issuedAtEpochSeconds = now, expiresAtEpochSeconds = now + 25 * 3600)
        assertThrows<JwtVerificationException> { verifier.verify(token) }
    }

    @Test
    fun `rejects a token without iat that expires more than 24 hours from now`() {
        val token = TestJwt.sign(secret, expiresAtEpochSeconds = System.currentTimeMillis() / 1000 + 25 * 3600)
        assertThrows<JwtVerificationException> { verifier.verify(token) }
    }

    @Test
    fun `accepts a token with a 24 hour lifetime`() {
        val now = System.currentTimeMillis() / 1000
        val token = TestJwt.sign(secret, issuedAtEpochSeconds = now, expiresAtEpochSeconds = now + 24 * 3600)
        assertEquals("bob", verifier.verify(token).nickname)
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
