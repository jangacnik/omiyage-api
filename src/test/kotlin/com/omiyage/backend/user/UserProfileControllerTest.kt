package com.omiyage.backend.user

import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
import com.omiyage.backend.config.MeishiProperties
import com.omiyage.backend.security.JwtAuthenticationFilter
import com.omiyage.backend.security.JwtVerifier
import com.omiyage.backend.security.SecurityConfig
import com.omiyage.backend.security.TestJwt
import io.mockk.every
import io.mockk.just
import io.mockk.Runs
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.patch
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

private const val TEST_SECRET = "user-profile-controller-secret"

@WebMvcTest(controllers = [UserProfileController::class])
@Import(SecurityConfig::class, JwtAuthenticationFilter::class, JwtVerifier::class)
@EnableConfigurationProperties(MeishiProperties::class)
@TestPropertySource(properties = ["omiyage.meishi.jwt-secret=$TEST_SECRET", "omiyage.meishi.api-url=http://localhost:8080"])
class UserProfileControllerTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @MockkBean
    lateinit var client: MeishiProfileClient

    private fun userToken() = TestJwt.sign(TEST_SECRET, role = "USER")

    private fun profile() = UserProfileDto(
        id = UUID.randomUUID(),
        nickname = "renamed",
        email = null,
        phone = null,
        firstName = null,
        lastName = null,
        addressLine1 = null,
        addressLine2 = null,
        postalCode = null,
        city = null,
        country = null,
    )

    @Test
    fun `changes the caller's own nickname`() {
        every { client.changeNickname(any(), "renamed") } returns profile()

        mockMvc.patch("/api/users/me/nickname") {
            header("Authorization", "Bearer ${userToken()}")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(mapOf("nickname" to "renamed"))
        }.andExpect { status { isOk() } }

        verify { client.changeNickname(any(), "renamed") }
    }

    @Test
    fun `rejects a blank nickname`() {
        mockMvc.patch("/api/users/me/nickname") {
            header("Authorization", "Bearer ${userToken()}")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(mapOf("nickname" to ""))
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `changes the caller's own password`() {
        every { client.changePassword(any(), "old-pass", "new-pass-123") } just Runs

        mockMvc.patch("/api/users/me/password") {
            header("Authorization", "Bearer ${userToken()}")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(mapOf("oldPassword" to "old-pass", "newPassword" to "new-pass-123"))
        }.andExpect { status { isNoContent() } }
    }

    @Test
    fun `a wrong current password surfaces as 401, not a raw 500`() {
        every { client.changePassword(any(), "wrong", "new-pass-123") } throws
            ResponseStatusException(HttpStatus.UNAUTHORIZED, "incorrect password")

        mockMvc.patch("/api/users/me/password") {
            header("Authorization", "Bearer ${userToken()}")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(mapOf("oldPassword" to "wrong", "newPassword" to "new-pass-123"))
        }.andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `profile update forwards only whitelisted fields`() {
        every { client.updateMe(any(), mapOf("city" to "Tokyo")) } returns profile()

        mockMvc.patch("/api/users/me") {
            header("Authorization", "Bearer ${userToken()}")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(mapOf("city" to "Tokyo", "role" to "ADMIN", "status" to "ACTIVE"))
        }.andExpect { status { isOk() } }

        verify { client.updateMe(any(), mapOf("city" to "Tokyo")) }
    }

    @Test
    fun `profile update forwards discordName`() {
        every { client.updateMe(any(), mapOf("discordName" to "bob_b")) } returns profile()

        mockMvc.patch("/api/users/me") {
            header("Authorization", "Bearer ${userToken()}")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(mapOf("discordName" to "bob_b"))
        }.andExpect { status { isOk() } }

        verify { client.updateMe(any(), mapOf("discordName" to "bob_b")) }
    }
}
