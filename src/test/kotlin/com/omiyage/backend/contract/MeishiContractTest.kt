package com.omiyage.backend.contract

import com.fasterxml.jackson.databind.ObjectMapper
import com.omiyage.backend.auth.MeishiAuthClient
import com.omiyage.backend.user.MeishiAdminClient
import com.omiyage.backend.user.MeishiNicknameClient
import com.omiyage.backend.user.MeishiProfileClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.header
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.RestClient
import org.springframework.web.server.ResponseStatusException

/** Pins the MeishiApi request/response shapes Omiyage depends on: if Meishi renames a field or route, these fail. */
class MeishiContractTest {

    private val builder = RestClient.builder().baseUrl("http://meishi.test")
    private val server = MockRestServiceServer.bindTo(builder).build()
    private val client = builder.build()
    private val objectMapper = ObjectMapper()

    private val userJson = """
        {"id":"3f2b8c1e-7a44-4a53-9d7e-0c9f4f5a1b11","nickname":"bob","email":"bob@example.com","phone":null,
         "firstName":"Bob","lastName":"B","addressLine1":null,"addressLine2":null,"postalCode":null,"city":"Tokyo",
         "country":"JP","avatarKey":null,"discordName":"bob_b","activated":true,"role":"USER","status":"ACTIVE"}
    """.trimIndent()

    @Test
    fun `login posts nickname and password and reads the token`() {
        server.expect(requestTo("http://meishi.test/api/auth/login")).andExpect(method(HttpMethod.POST))
            .andRespond(withSuccess("""{"token":"jwt-token"}""", MediaType.APPLICATION_JSON))

        assertEquals("jwt-token", MeishiAuthClient(client).login("bob", "pw"))
        server.verify()
    }

    @Test
    fun `login maps a Meishi 401 to 401`() {
        server.expect(requestTo("http://meishi.test/api/auth/login")).andRespond(withStatus(HttpStatus.UNAUTHORIZED))

        val e = assertThrows<ResponseStatusException> { MeishiAuthClient(client).login("bob", "bad") }
        assertEquals(HttpStatus.UNAUTHORIZED, e.statusCode)
    }

    @Test
    fun `login lets a Meishi 5xx escape for the 502 mapping`() {
        server.expect(requestTo("http://meishi.test/api/auth/login")).andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR))

        assertThrows<HttpServerErrorException> { MeishiAuthClient(client).login("bob", "pw") }
    }

    @Test
    fun `users me exposes id, nickname, role and status`() {
        server.expect(requestTo("http://meishi.test/api/users/me")).andExpect(header("Authorization", "Bearer t"))
            .andRespond(withSuccess(userJson, MediaType.APPLICATION_JSON))

        val me = MeishiAuthClient(client).getMe("t")
        assertEquals("bob", me.nickname)
        assertEquals("USER", me.role)
        assertEquals("ACTIVE", me.status)
    }

    @Test
    fun `profile client reads the profile fields`() {
        server.expect(requestTo("http://meishi.test/api/users/me")).andRespond(withSuccess(userJson, MediaType.APPLICATION_JSON))

        val profile = MeishiProfileClient(client, objectMapper).getMe("Bearer t")
        assertEquals("Tokyo", profile.city)
        assertEquals("bob@example.com", profile.email)
    }

    @Test
    fun `profile client passes a Meishi 4xx error message through`() {
        server.expect(requestTo("http://meishi.test/api/users/me/nickname"))
            .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON).body("""{"error":"nickname taken"}"""))

        val e = assertThrows<ResponseStatusException> { MeishiProfileClient(client, objectMapper).changeNickname("Bearer t", "bob") }
        assertEquals(HttpStatus.CONFLICT, e.statusCode)
        assertEquals("nickname taken", e.reason)
    }

    @Test
    fun `admin client reads the user list`() {
        server.expect(requestTo("http://meishi.test/api/users")).andRespond(withSuccess("[$userJson]", MediaType.APPLICATION_JSON))

        val users = MeishiAdminClient(client, objectMapper).listUsers("Bearer t")
        assertEquals(1, users.size)
        assertFalse(users[0].nickname.isNullOrBlank())
    }

    @Test
    fun `nickname client reads id and nickname pairs`() {
        server.expect(requestTo("http://meishi.test/api/users/nicknames"))
            .andRespond(withSuccess("""[{"id":"3f2b8c1e-7a44-4a53-9d7e-0c9f4f5a1b11","nickname":"bob"}]""", MediaType.APPLICATION_JSON))

        assertEquals("bob", MeishiNicknameClient(client, objectMapper).nicknames("Bearer t").single().nickname)
    }
}
