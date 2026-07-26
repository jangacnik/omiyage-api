package com.omiyage.backend.user

import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
import com.omiyage.backend.config.MeishiProperties
import com.omiyage.backend.security.JwtAuthenticationFilter
import com.omiyage.backend.security.JwtVerifier
import com.omiyage.backend.security.SecurityConfig
import com.omiyage.backend.security.TestJwt
import io.mockk.every
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import java.time.OffsetDateTime
import java.util.UUID

private const val TEST_SECRET = "profile-controller-secret"

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
    lateinit var service: UserProfileService

    private val userId = UUID.randomUUID()
    private fun token() = TestJwt.sign(TEST_SECRET, subject = userId.toString(), nickname = "bob", role = "USER")

    private fun profile(email: String? = null, phone: String? = null, shippingAddress: String? = null) = UserProfile(
        id = userId,
        nickname = "bob",
        email = email,
        phone = phone,
        shippingAddress = shippingAddress,
        createdAt = OffsetDateTime.now(),
        updatedAt = OffsetDateTime.now(),
    )

    @Test
    fun `getMe returns the caller's profile`() {
        every { service.ensureProfile(any()) } returns profile(email = "bob@example.com")

        mockMvc.get("/api/users/me") { header("Authorization", "Bearer ${token()}") }
            .andExpect {
                status { isOk() }
                jsonPath("$.nickname") { value("bob") }
                jsonPath("$.email") { value("bob@example.com") }
            }
    }

    @Test
    fun `getMe requires authentication`() {
        mockMvc.get("/api/users/me").andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `updateMe forwards the parsed request to the service`() {
        every { service.updateProfile(any(), "new@example.com", "+81-90-0000-0000", "Tokyo") } returns
            profile(email = "new@example.com", phone = "+81-90-0000-0000", shippingAddress = "Tokyo")

        mockMvc.patch("/api/users/me") {
            header("Authorization", "Bearer ${token()}")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(
                UpdateProfileRequest(email = "new@example.com", phone = "+81-90-0000-0000", shippingAddress = "Tokyo"),
            )
        }.andExpect {
            status { isOk() }
            jsonPath("$.shippingAddress") { value("Tokyo") }
        }
    }

    @Test
    fun `updateMe rejects an invalid email`() {
        mockMvc.patch("/api/users/me") {
            header("Authorization", "Bearer ${token()}")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(UpdateProfileRequest(email = "not-an-email"))
        }.andExpect { status { isBadRequest() } }
    }
}
