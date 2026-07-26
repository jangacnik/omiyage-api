package com.omiyage.backend.catalog

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
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.util.UUID

private const val TEST_SECRET = "category-controller-secret"

@WebMvcTest(controllers = [CategoryController::class])
@Import(SecurityConfig::class, JwtAuthenticationFilter::class, JwtVerifier::class)
@EnableConfigurationProperties(MeishiProperties::class)
@TestPropertySource(properties = ["omiyage.meishi.jwt-secret=$TEST_SECRET", "omiyage.meishi.api-url=http://localhost:8080"])
class CategoryControllerTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @MockkBean
    lateinit var service: CategoryService

    @Test
    fun `list requires authentication`() {
        mockMvc.get("/api/categories").andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `list returns categories for any authenticated user`() {
        every { service.list() } returns listOf(CategoryDto(UUID.randomUUID(), "Snacks", "snacks"))

        mockMvc.get("/api/categories") { header("Authorization", "Bearer ${TestJwt.sign(TEST_SECRET, role = "USER")}") }
            .andExpect {
                status { isOk() }
                jsonPath("$[0].name") { value("Snacks") }
            }
    }
}
