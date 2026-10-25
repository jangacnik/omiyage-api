package com.omiyage.backend.media

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
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

private const val TEST_SECRET = "media-controller-secret"

@WebMvcTest(controllers = [MediaController::class])
@Import(SecurityConfig::class, JwtAuthenticationFilter::class, JwtVerifier::class)
@EnableConfigurationProperties(MeishiProperties::class)
@TestPropertySource(properties = ["omiyage.meishi.jwt-secret=$TEST_SECRET", "omiyage.meishi.api-url=http://localhost:8080"])
class MediaControllerTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @MockkBean
    lateinit var service: MediaService

    private fun token() = TestJwt.sign(TEST_SECRET, role = "USER")

    @Test
    fun `upload requires authentication`() {
        val file = MockMultipartFile("file", "photo.jpg", "image/jpeg", "fake-bytes".toByteArray())
        mockMvc.perform(multipart("/api/images").file(file)).andExpect(status().isUnauthorized)
    }

    @Test
    fun `upload returns the generated key`() {
        every { service.upload(any()) } returns "uploads/generated-key.jpg"
        val file = MockMultipartFile("file", "photo.jpg", "image/jpeg", "fake-bytes".toByteArray())

        mockMvc.perform(
            multipart("/api/images").file(file).header("Authorization", "Bearer ${token()}"),
        ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.key").value("uploads/generated-key.jpg"))
    }

    @Test
    fun `download is public and serves the stored bytes with their content type`() {
        every { service.get("uploads/abc.jpeg") } returns Media("uploads/abc.jpeg", "image/jpeg", "fake-bytes".toByteArray())

        mockMvc.perform(get("/api/images/uploads/abc.jpeg"))
            .andExpect(status().isOk)
            .andExpect(content().contentType(MediaType.IMAGE_JPEG))
            .andExpect(content().bytes("fake-bytes".toByteArray()))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
    }
}
