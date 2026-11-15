package com.omiyage.backend.auth

import com.omiyage.backend.web.toHttpFailure
import com.omiyage.backend.errors.orFail
import com.omiyage.backend.errors.errorOrFail
import com.omiyage.backend.config.MeishiProperties
import com.omiyage.backend.config.MeishiRestClientConfig
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.web.server.ResponseStatusException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder

class MeishiAuthClientExchangeTest {

    private lateinit var server: HttpServer
    private lateinit var client: MeishiAuthClient
    private var handler: (HttpExchange) -> Unit = {}
    private var contentType: String? = null
    private var form: Map<String, String> = emptyMap()

    @BeforeEach
    fun startServer() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/api/oauth/token") { exchange ->
            contentType = exchange.requestHeaders.getFirst("Content-Type")
            form = String(exchange.requestBody.readBytes()).split("&").associate {
                val (key, value) = it.split("=", limit = 2)
                key to URLDecoder.decode(value, Charsets.UTF_8)
            }
            handler(exchange)
        }
        server.start()
        val props = MeishiProperties(jwtSecret = "s", apiUrl = "http://127.0.0.1:${server.address.port}")
        client = MeishiAuthClient(MeishiRestClientConfig().meishiRestClient(props))
    }

    @AfterEach
    fun stopServer() = server.stop(0)

    private fun HttpExchange.respondJson(status: Int, body: String) {
        responseHeaders.add("Content-Type", "application/json")
        val bytes = body.toByteArray()
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private fun exchange() = client.exchangeCode("omiyage", "the-code", "the-verifier", "https://omiyage.example/auth/callback")

    private fun statusOfFailure(): Int = exchange().errorOrFail().toHttpFailure().status.value()

    @Test
    fun `posts the grant as a form and returns the access token`() {
        handler = { it.respondJson(200, """{"access_token":"jwt","token_type":"Bearer","expires_in":600}""") }

        assertEquals("jwt", exchange().orFail())
        assertEquals("application/x-www-form-urlencoded", contentType)
        assertEquals(
            mapOf(
                "grant_type" to "authorization_code",
                "client_id" to "omiyage",
                "code" to "the-code",
                "code_verifier" to "the-verifier",
                "redirect_uri" to "https://omiyage.example/auth/callback",
            ),
            form,
        )
    }

    @Test
    fun `a rejected code is 401`() {
        handler = { it.respondJson(400, """{"error":"invalid_grant"}""") }

        assertEquals(401, statusOfFailure())
    }

    @Test
    fun `a response without an access token is 401`() {
        handler = { it.respondJson(200, """{"token_type":"Bearer"}""") }

        assertEquals(401, statusOfFailure())
    }

    @Test
    fun `a disabled hosted login at MeishiApi is 503`() {
        handler = { it.respondJson(404, """{"error":"oauth_disabled"}""") }

        assertEquals(503, statusOfFailure())
    }

    @Test
    fun `MeishiApi throttling is passed on as 429`() {
        handler = { it.respondJson(429, """{"error":"too many requests"}""") }

        assertEquals(429, statusOfFailure())
    }
}
