package com.omiyage.backend.catalog

import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
import com.omiyage.backend.config.MeishiProperties
import com.omiyage.backend.security.JwtAuthenticationFilter
import com.omiyage.backend.security.JwtVerifier
import com.omiyage.backend.security.SecurityConfig
import com.omiyage.backend.security.TestJwt
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.post
import java.util.UUID

private const val TEST_SECRET = "admin-category-controller-secret"

@WebMvcTest(controllers = [AdminCategoryController::class])
@Import(SecurityConfig::class, JwtAuthenticationFilter::class, JwtVerifier::class)
@EnableConfigurationProperties(MeishiProperties::class)
@TestPropertySource(properties = ["omiyage.meishi.jwt-secret=$TEST_SECRET", "omiyage.meishi.api-url=http://localhost:8080"])
class AdminCategoryControllerTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @MockkBean
    lateinit var service: CategoryService

    private fun userToken() = TestJwt.sign(TEST_SECRET, role = "USER")
    private fun adminToken() = TestJwt.sign(TEST_SECRET, role = "ADMIN")

    @Test
    fun `create is rejected for a non-admin user`() {
        mockMvc.post("/api/admin/categories") {
            header("Authorization", "Bearer ${userToken()}")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(CreateCategoryRequest("Snacks", "snacks"))
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `create succeeds for an admin`() {
        every { service.create("Snacks", "snacks") } returns CategoryDto(UUID.randomUUID(), "Snacks", "snacks")

        mockMvc.post("/api/admin/categories") {
            header("Authorization", "Bearer ${adminToken()}")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(CreateCategoryRequest("Snacks", "snacks"))
        }.andExpect {
            status { isCreated() }
            jsonPath("$.slug") { value("snacks") }
        }
    }

    @Test
    fun `delete succeeds for an admin`() {
        val id = UUID.randomUUID()
        every { service.delete(id) } just Runs

        mockMvc.delete("/api/admin/categories/$id") { header("Authorization", "Bearer ${adminToken()}") }
            .andExpect { status { isNoContent() } }
    }
}
