package com.omiyage.backend.config

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.springframework.mock.env.MockEnvironment

class JwtSecretValidatorTest {

    private fun validate(secret: String, vararg profiles: String) {
        val environment = MockEnvironment().apply { setActiveProfiles(*profiles) }
        JwtSecretValidator(MeishiProperties(jwtSecret = secret, apiUrl = "http://localhost:8080"), environment)
    }

    @Test
    fun `rejects the dev default outside dev and test`() {
        assertThrows<IllegalStateException> { validate("dev-secret-change-me", "prod") }
    }

    @Test
    fun `rejects a secret shorter than 32 bytes outside dev and test`() {
        assertThrows<IllegalStateException> { validate("short-secret") }
    }

    @Test
    fun `rejects an unset or unresolved secret even in dev`() {
        assertThrows<IllegalStateException> { validate("", "dev") }
        assertThrows<IllegalStateException> { validate("\${MEISHI_JWT_SECRET}", "dev") }
    }

    @Test
    fun `counts bytes not characters for the minimum length`() {
        assertThrows<IllegalStateException> { validate("a".repeat(31), "prod") }
        assertThrows<IllegalStateException> { validate("é".repeat(15), "prod") }
        assertDoesNotThrow { validate("é".repeat(16), "prod") }
    }

    @Test
    fun `accepts a strong secret`() {
        assertDoesNotThrow { validate("a".repeat(32), "prod") }
    }

    @Test
    fun `accepts a weak secret under the dev profile`() {
        assertDoesNotThrow { validate("dev-secret-change-me", "dev") }
    }
}
