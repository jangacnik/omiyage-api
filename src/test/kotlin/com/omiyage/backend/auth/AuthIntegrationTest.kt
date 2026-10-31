package com.omiyage.backend.auth

import com.fasterxml.jackson.databind.ObjectMapper
import com.omiyage.backend.PostgresIntegrationTest
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.server.ResponseStatusException
import java.net.InetSocketAddress

/** Exercises the real HTTP boundary against a stub standing in for MeishiApi, so the
 *  request/response contract (paths, JSON shape, error status mapping) is actually verified
 *  rather than assumed. Uses the JDK's built-in HttpServer instead of a mocking library to
 *  avoid dragging in a whole HTTP-client/Jetty dependency chain for three tests. */
@SpringBootTest
class AuthIntegrationTest : PostgresIntegrationTest() {

    companion object {
        private val mapper = ObjectMapper()
        private val server: HttpServer = HttpServer.create(InetSocketAddress(0), 0).apply { start() }

        private var loginHandler: (HttpExchange) -> Unit = { it.respondJson(404, """{"error":"not stubbed"}""") }
        private var meHandler: (HttpExchange) -> Unit = { it.respondJson(404, """{"error":"not stubbed"}""") }

        init {
            server.createContext("/api/auth/login") { exchange -> loginHandler(exchange) }
            server.createContext("/api/users/me") { exchange -> meHandler(exchange) }
        }

        @JvmStatic
        @DynamicPropertySource
        fun meishiProperties(registry: DynamicPropertyRegistry) {
            registry.add("omiyage.meishi.api-url") { "http://localhost:${server.address.port}" }
        }

        private fun HttpExchange.respondJson(status: Int, body: String) {
            responseHeaders.add("Content-Type", "application/json")
            val bytes = body.toByteArray()
            sendResponseHeaders(status, bytes.size.toLong())
            responseBody.use { it.write(bytes) }
        }

        private fun HttpExchange.readBodyAsMap(): Map<*, *> = mapper.readValue(requestBody.readBytes(), Map::class.java)
    }

    @Autowired
    lateinit var authService: AuthService

    @AfterEach
    fun resetStubs() {
        loginHandler = { it.respondJson(404, """{"error":"not stubbed"}""") }
        meHandler = { it.respondJson(404, """{"error":"not stubbed"}""") }
    }

    @Test
    fun `logs in successfully against a real HTTP round trip`() {
        loginHandler = { exchange ->
            val body = exchange.readBodyAsMap()
            assertEquals("bob", body["nickname"])
            assertEquals("hunter2", body["password"])
            exchange.respondJson(200, """{"token":"signed-token"}""")
        }
        meHandler = { exchange ->
            assertEquals("Bearer signed-token", exchange.requestHeaders.getFirst("Authorization"))
            exchange.respondJson(
                200,
                """{"id":"11111111-1111-1111-1111-111111111111","nickname":"bob","role":"USER","status":"ACTIVE","activated":true,"createdAt":"2026-01-01T00:00:00Z"}""",
            )
        }

        assertEquals("signed-token", authService.login("bob", "hunter2", "1.2.3.4"))
    }

    @Test
    fun `surfaces invalid credentials as 401`() {
        loginHandler = { it.respondJson(401, """{"error":"invalid credentials"}""") }

        val exception = assertThrows(ResponseStatusException::class.java) { authService.login("bob", "wrong", "1.2.3.4") }
        assertEquals(401, exception.statusCode.value())
    }

    @Test
    fun `rejects a frozen account as 403`() {
        loginHandler = { it.respondJson(200, """{"token":"signed-token"}""") }
        meHandler = {
            it.respondJson(
                200,
                """{"id":"11111111-1111-1111-1111-111111111111","nickname":"bob","role":"USER","status":"FROZEN","activated":true,"createdAt":"2026-01-01T00:00:00Z"}""",
            )
        }

        val exception = assertThrows(ResponseStatusException::class.java) { authService.login("bob", "hunter2", "1.2.3.4") }
        assertEquals(403, exception.statusCode.value())
    }
}
