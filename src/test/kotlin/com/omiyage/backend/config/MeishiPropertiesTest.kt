package com.omiyage.backend.config

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MeishiPropertiesTest {

    private fun props(enabled: Boolean = true, clientId: String = "omiyage", url: String = "https://meishi.example/oauth/authorize") =
        MeishiProperties(jwtSecret = "s", apiUrl = "http://localhost:8080", ssoEnabled = enabled, ssoClientId = clientId, ssoAuthorizeUrl = url)

    @Test
    fun `sso is off by default`() {
        assertFalse(MeishiProperties(jwtSecret = "s", apiUrl = "http://localhost:8080").ssoActive)
    }

    @Test
    fun `sso is active with the flag, a client id and an https authorize url`() {
        assertTrue(props().ssoActive)
    }

    @Test
    fun `sso is not active when any part is missing or insecure`() {
        assertFalse(props(enabled = false).ssoActive)
        assertFalse(props(clientId = " ").ssoActive)
        assertFalse(props(url = "").ssoActive)
        assertFalse(props(url = "http://meishi.example/oauth/authorize").ssoActive)
        assertFalse(props(url = "javascript:alert(1)").ssoActive)
    }
}
