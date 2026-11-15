package com.omiyage.backend.auth

import com.omiyage.backend.config.MeishiProperties
import com.omiyage.backend.config.MeishiRestClientConfig
import com.omiyage.backend.errors.AuthError
import com.omiyage.backend.errors.errorOrFail
import com.omiyage.backend.errors.orFail
import com.omiyage.backend.web.HttpFailure
import com.omiyage.backend.web.toHttpFailure
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.http.HttpStatus
import java.net.InetAddress
import java.net.InetSocketAddress

class MeishiAuthClientLoginTest {

    private lateinit var server: HttpServer
    private lateinit var client: MeishiAuthClient
    private var handler: (HttpExchange) -> Unit = {}
    private var requestBody: String? = null

    @BeforeEach
    fun startServer() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/api/auth/login") { exchange ->
            requestBody = String(exchange.requestBody.readBytes())
            handler(exchange)
        }
        server.start()
        val props = MeishiProperties(jwtSecret = "s", apiUrl = "http://127.0.0.1:${server.address.port}")
        client = MeishiAuthClient(MeishiRestClientConfig().meishiRestClient(props))
    }

    @AfterEach
    fun stopServer() = server.stop(0)

    private fun HttpExchange.respondJson(status: Int, body: String, headers: Map<String, String> = emptyMap()) {
        responseHeaders.add("Content-Type", "application/json")
        headers.forEach { (name, value) -> responseHeaders.add(name, value) }
        val bytes = body.toByteArray()
        sendResponseHeaders(status, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private fun login() = client.login("bob", "hunter2")

    private fun failure(): HttpFailure = login().errorOrFail().toHttpFailure()

    @Test
    fun `posts the credentials and returns the token`() {
        handler = { it.respondJson(200, """{"token":"jwt"}""") }

        assertEquals("jwt", login().orFail())
        assertEquals("""{"nickname":"bob","password":"hunter2"}""", requestBody)
    }

    @Test
    fun `a response without a token is 401 invalid credentials`() {
        handler = { it.respondJson(200, "null") }

        assertEquals(HttpFailure(HttpStatus.UNAUTHORIZED, "invalid credentials"), failure())
    }

    @ParameterizedTest
    @ValueSource(ints = [400, 401, 403, 404, 409, 423])
    fun `every other client error stays one 401 invalid credentials`(status: Int) {
        handler = { it.respondJson(status, """{"error":"nope"}""") }

        assertEquals(AuthError.InvalidCredentials, login().errorOrFail())
        assertEquals(HttpFailure(HttpStatus.UNAUTHORIZED, "invalid credentials"), failure())
    }

    @Test
    fun `MeishiApi throttling is passed on as 429, not as wrong credentials`() {
        handler = { it.respondJson(429, """{"error":"too many requests"}""") }

        assertEquals(HttpFailure(HttpStatus.TOO_MANY_REQUESTS, "too many login attempts"), failure())
    }

    @Test
    fun `throttling carries the Retry-After seconds MeishiApi sent`() {
        handler = { it.respondJson(429, """{"error":"too many requests"}""", mapOf("Retry-After" to "37")) }

        assertEquals(HttpFailure(HttpStatus.TOO_MANY_REQUESTS, "too many login attempts", retryAfterSeconds = 37), failure())
    }

    @ParameterizedTest
    @ValueSource(strings = ["Wed, 21 Oct 2026 07:28:00 GMT", "soon", "-5", ""])
    fun `a Retry-After that is not a non-negative number of seconds is dropped`(value: String) {
        handler = { it.respondJson(429, """{"error":"too many requests"}""", mapOf("Retry-After" to value)) }

        assertEquals(HttpFailure(HttpStatus.TOO_MANY_REQUESTS, "too many login attempts"), failure())
    }
}
