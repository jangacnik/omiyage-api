package com.omiyage.backend.auth

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.equalToJson
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.omiyage.backend.PostgresIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.server.ResponseStatusException

/** Exercises the real HTTP boundary against a WireMock stub standing in for MeishiApi, so the
 *  request/response contract (paths, JSON shape, error status mapping) is actually verified
 *  rather than assumed. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class AuthIntegrationTest : PostgresIntegrationTest() {

    companion object {
        private val wireMock: WireMockServer = WireMockServer(0).apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun meishiProperties(registry: DynamicPropertyRegistry) {
            registry.add("omiyage.meishi.api-url") { "http://localhost:${wireMock.port()}" }
        }
    }

    @Autowired
    lateinit var authService: AuthService

    @BeforeEach
    fun resetStubs() {
        wireMock.resetAll()
    }

    @Test
    fun `logs in successfully against a real HTTP round trip`() {
        wireMock.stubFor(
            post(urlEqualTo("/api/auth/login"))
                .withRequestBody(equalToJson("""{"nickname":"bob","password":"hunter2"}"""))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("""{"token":"signed-token"}""")),
        )
        wireMock.stubFor(
            get(urlEqualTo("/api/users/me"))
                .withHeader("Authorization", equalTo("Bearer signed-token"))
                .willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("""{"id":"11111111-1111-1111-1111-111111111111","nickname":"bob","role":"USER","status":"ACTIVE","activated":true,"createdAt":"2026-01-01T00:00:00Z"}"""),
                ),
        )

        assertEquals("signed-token", authService.login("bob", "hunter2"))
    }

    @Test
    fun `surfaces invalid credentials as 401`() {
        wireMock.stubFor(
            post(urlEqualTo("/api/auth/login"))
                .willReturn(aResponse().withStatus(401).withHeader("Content-Type", "application/json").withBody("""{"error":"invalid credentials"}""")),
        )

        val exception = assertThrows(ResponseStatusException::class.java) { authService.login("bob", "wrong") }
        assertEquals(401, exception.statusCode.value())
    }

    @Test
    fun `rejects a frozen account as 403`() {
        wireMock.stubFor(
            post(urlEqualTo("/api/auth/login"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("""{"token":"signed-token"}""")),
        )
        wireMock.stubFor(
            get(urlEqualTo("/api/users/me"))
                .willReturn(
                    aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("""{"id":"11111111-1111-1111-1111-111111111111","nickname":"bob","role":"USER","status":"FROZEN","activated":true,"createdAt":"2026-01-01T00:00:00Z"}"""),
                ),
        )

        val exception = assertThrows(ResponseStatusException::class.java) { authService.login("bob", "hunter2") }
        assertEquals(403, exception.statusCode.value())
    }
}
