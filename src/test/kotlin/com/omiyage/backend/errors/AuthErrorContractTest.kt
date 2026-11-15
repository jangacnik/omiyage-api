package com.omiyage.backend.errors

import com.omiyage.backend.auth.LoginRequest
import com.omiyage.backend.auth.MeishiExchangeRequest
import com.omiyage.backend.security.TestJwt
import com.omiyage.backend.user.ChangeNicknameRequest
import com.omiyage.backend.user.ChangePasswordRequest
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import java.net.InetSocketAddress
import java.util.UUID

private const val VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
private const val REDIRECT = "https://omiyage.example/auth/callback"
private const val SECRET = "integration-test-secret"
private const val ACTIVE_USER =
    """{"id":"11111111-1111-1111-1111-111111111111","nickname":"bob","role":"USER","status":"ACTIVE","activated":true,"email":null,"phone":null,"firstName":null,"lastName":null,"addressLine1":null,"addressLine2":null,"postalCode":null,"city":null,"country":null}"""
private const val FROZEN_USER =
    """{"id":"11111111-1111-1111-1111-111111111111","nickname":"bob","role":"USER","status":"FROZEN","activated":true}"""

/** Auth relay and profile proxy against a stub standing in for MeishiApi: every way MeishiApi's
 *  answer turns into an error response of this API. */
class AuthErrorContractTest : ErrorContractTest() {

    companion object {
        private val server: HttpServer = HttpServer.create(InetSocketAddress(0), 0).apply { start() }

        private val activeUser: (HttpExchange) -> Unit = { it.respond(200, ACTIVE_USER) }
        private var loginHandler: (HttpExchange) -> Unit = { it.respond(404, """{"error":"not stubbed"}""") }
        private var tokenHandler: (HttpExchange) -> Unit = { it.respond(404, """{"error":"not stubbed"}""") }
        private var meHandler: (HttpExchange) -> Unit = activeUser
        private var profileWriteHandler: (HttpExchange) -> Unit = { it.respond(404, """{"error":"not stubbed"}""") }

        init {
            server.createContext("/api/auth/login") { loginHandler(it) }
            server.createContext("/api/oauth/token") { tokenHandler(it) }
            server.createContext("/api/users/me") { exchange ->
                if (exchange.requestMethod == "GET") meHandler(exchange) else profileWriteHandler(exchange)
            }
        }

        @JvmStatic
        @DynamicPropertySource
        fun meishiProperties(registry: DynamicPropertyRegistry) {
            registry.add("omiyage.meishi.api-url") { "http://localhost:${server.address.port}" }
            registry.add("omiyage.meishi.sso-enabled") { "true" }
            registry.add("omiyage.meishi.sso-client-id") { "omiyage" }
            registry.add("omiyage.meishi.sso-authorize-url") { "https://meishi.example/oauth/authorize" }
        }

        private fun HttpExchange.respond(status: Int, body: String? = null) {
            requestBody.readBytes()
            if (body == null) {
                sendResponseHeaders(status, -1)
                close()
                return
            }
            responseHeaders.add("Content-Type", "application/json")
            val bytes = body.toByteArray()
            sendResponseHeaders(status, bytes.size.toLong())
            responseBody.use { it.write(bytes) }
        }
    }

    @AfterEach
    fun resetStubs() {
        loginHandler = { it.respond(404, """{"error":"not stubbed"}""") }
        tokenHandler = { it.respond(404, """{"error":"not stubbed"}""") }
        meHandler = activeUser
        profileWriteHandler = { it.respond(404, """{"error":"not stubbed"}""") }
    }

    private fun anonymous(path: String, body: Any, clientIp: String = "203.0.113.${(1..250).random()}"): MvcResult =
        mockMvc.perform(
            request(HttpMethod.POST, path)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Forwarded-For", clientIp)
                .content(objectMapper.writeValueAsString(body)),
        ).andReturn()

    private fun login(nickname: String = "nick-${UUID.randomUUID()}", clientIp: String = "203.0.113.${(1..250).random()}") =
        anonymous("/api/auth/login", LoginRequest(nickname, "hunter2"), clientIp)

    private fun exchange(clientIp: String = "198.51.100.${(1..250).random()}") =
        anonymous("/api/auth/meishi/exchange", MeishiExchangeRequest("the-code", VERIFIER, REDIRECT), clientIp)

    private fun acceptableToken(): String = TestJwt.sign(SECRET, subject = UUID.randomUUID().toString(), role = "USER")

    @Test
    fun `login rejected by MeishiApi is 401`() {
        loginHandler = { it.respond(401, """{"error":"bad"}""") }
        assertError(login(), 401, "unauthorized", "invalid credentials")
    }

    // Pins today's behaviour for issue #108 (MeishiApi's 429 is reported as 401); the fix is a separate PR.
    @Test
    fun `login throttled by MeishiApi is currently 401 - issue 108`() {
        loginHandler = { it.respond(429, """{"error":"slow down"}""") }
        assertError(login(), 401, "unauthorized", "invalid credentials")
    }

    @Test
    fun `login answered without a token is 401`() {
        loginHandler = { it.respond(200) }
        assertError(login(), 401, "unauthorized", "invalid credentials")
    }

    @Test
    fun `login of a frozen account is 403`() {
        loginHandler = { it.respond(200, """{"token":"signed-token"}""") }
        meHandler = { it.respond(200, FROZEN_USER) }
        assertForbidden(login(), "account is frozen")
    }

    @Test
    fun `login whose status lookup fails is 401`() {
        loginHandler = { it.respond(200, """{"token":"signed-token"}""") }
        meHandler = { it.respond(401, """{"error":"nope"}""") }
        assertError(login(), 401, "unauthorized", "invalid credentials")
    }

    @Test
    fun `too many logins for one nickname is 429`() {
        loginHandler = { it.respond(401, """{"error":"bad"}""") }
        val nickname = "busy-${UUID.randomUUID()}"
        val ip = "192.0.2.${(1..250).random()}"
        repeat(10) { assertEquals(401, login(nickname, ip).response.status) }
        assertError(login(nickname, ip), 429, "too_many_requests", "too many login attempts")
    }

    @Test
    fun `too many exchanges from one address is 429`() {
        tokenHandler = { it.respond(400, """{"error":"bad"}""") }
        val ip = "192.0.2.${(1..250).random()}"
        repeat(20) { assertEquals(401, exchange(ip).response.status) }
        assertError(exchange(ip), 429, "too_many_requests", "too many login attempts")
    }

    @Test
    fun `an invalid exchange request is 400`() {
        val result = anonymous("/api/auth/meishi/exchange", MeishiExchangeRequest("", VERIFIER, REDIRECT))
        assertBadRequest(result, "invalid request")
    }

    @Test
    fun `exchange when MeishiApi has no sso is 503`() {
        tokenHandler = { it.respond(404, """{"error":"off"}""") }
        assertError(exchange(), 503, "service_unavailable", "meishi sso is not enabled")
    }

    @Test
    fun `exchange throttled by MeishiApi is 429`() {
        tokenHandler = { it.respond(429, """{"error":"slow down"}""") }
        assertError(exchange(), 429, "too_many_requests", "too many login attempts")
    }

    @Test
    fun `exchange of a rejected code is 401`() {
        tokenHandler = { it.respond(400, """{"error":"invalid_grant"}""") }
        assertError(exchange(), 401, "unauthorized", "invalid authorization code")
    }

    @Test
    fun `exchange answered without an access token is 401`() {
        tokenHandler = { it.respond(200, "{}") }
        assertError(exchange(), 401, "unauthorized", "invalid authorization code")
    }

    @Test
    fun `exchange handing back an unusable token is 502`() {
        tokenHandler = { it.respond(200, """{"access_token":"garbage"}""") }
        assertError(exchange(), 502, "bad_gateway", "identity provider returned an unusable token")
    }

    @Test
    fun `exchange of a frozen account is 403`() {
        val token = acceptableToken()
        tokenHandler = { it.respond(200, """{"access_token":"$token"}""") }
        meHandler = { it.respond(200, FROZEN_USER) }
        assertForbidden(exchange(), "account is frozen")
    }

    @Test
    fun `exchange whose status lookup fails is 401`() {
        val token = acceptableToken()
        tokenHandler = { it.respond(200, """{"access_token":"$token"}""") }
        meHandler = { it.respond(401, """{"error":"nope"}""") }
        assertError(exchange(), 401, "unauthorized", "invalid credentials")
    }

    @Test
    fun `profile update rejected by MeishiApi passes its status and message through`() {
        profileWriteHandler = { it.respond(400, """{"error":"invalid email"}""") }
        val result = send(newCaller(), HttpMethod.PATCH, "/api/users/me", mapOf("email" to "nope"))
        assertBadRequest(result, "invalid email")
    }

    @Test
    fun `nickname change rejected by MeishiApi passes its status and message through`() {
        profileWriteHandler = { it.respond(409, """{"error":"nickname taken"}""") }
        val result = send(newCaller(), HttpMethod.PATCH, "/api/users/me/nickname", ChangeNicknameRequest("taken"))
        assertConflict(result, "nickname taken")
    }

    @Test
    fun `password change rejected by MeishiApi passes its status and message through`() {
        profileWriteHandler = { it.respond(400, """{"error":"wrong password"}""") }
        val result = send(newCaller(), HttpMethod.PATCH, "/api/users/me/password", ChangePasswordRequest("old", "new"))
        assertBadRequest(result, "wrong password")
    }

    @Test
    fun `a rejection without an error body falls back to the status text`() {
        profileWriteHandler = { it.respond(400, "not json") }
        val result = send(newCaller(), HttpMethod.PATCH, "/api/users/me/nickname", ChangeNicknameRequest("taken"))
        assertBadRequest(result, "Bad Request")
    }

    @Test
    fun `an empty profile answer is 502`() {
        profileWriteHandler = { it.respond(200) }
        val result = send(newCaller(), HttpMethod.PATCH, "/api/users/me/nickname", ChangeNicknameRequest("fresh"))
        assertError(result, 502, "bad_gateway", "empty response from meishi")
    }
}

/** Without the SSO flag the exchange route does not exist, whatever the body looks like. */
class SsoDisabledErrorContractTest : ErrorContractTest() {

    @Test
    fun `exchange with sso disabled is 404`() {
        val body = MeishiExchangeRequest("the-code", VERIFIER, REDIRECT)
        val result = mockMvc.perform(
            request(HttpMethod.POST, "/api/auth/meishi/exchange")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)),
        ).andReturn()
        assertNotFound(result, "meishi sso is disabled")
    }
}
