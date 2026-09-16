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
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.math.BigDecimal
import java.util.UUID

private const val TEST_SECRET = "admin-item-template-controller-secret"

@WebMvcTest(controllers = [AdminItemTemplateController::class])
@Import(SecurityConfig::class, JwtAuthenticationFilter::class, JwtVerifier::class)
@EnableConfigurationProperties(MeishiProperties::class)
@TestPropertySource(properties = ["omiyage.meishi.jwt-secret=$TEST_SECRET", "omiyage.meishi.api-url=http://localhost:8080"])
class AdminItemTemplateControllerTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @MockkBean
    lateinit var service: ItemTemplateService

    private fun userToken() = TestJwt.sign(TEST_SECRET, role = "USER")
    private fun adminToken() = TestJwt.sign(TEST_SECRET, role = "ADMIN")

    private fun sampleRequest() = ItemTemplateRequest(
        name = "Kit Kat box",
        title = "Kit Kat matcha 12-pack",
        description = "Sealed box",
        priceAmount = BigDecimal("12.00"),
        boughtPrice = BigDecimal("1200"),
        weight = BigDecimal("300"),
    )

    @Test
    fun `list is rejected for a non-admin user`() {
        mockMvc.get("/api/admin/item-templates") { header("Authorization", "Bearer ${userToken()}") }
            .andExpect { status { isForbidden() } }
    }

    @Test
    fun `create succeeds for an admin`() {
        val request = sampleRequest()
        every { service.create(request) } returns ItemTemplateDto(
            id = UUID.randomUUID(),
            name = request.name,
            title = request.title,
            description = request.description,
            priceAmount = request.priceAmount,
            boughtPrice = request.boughtPrice,
            weight = request.weight,
        )

        mockMvc.post("/api/admin/item-templates") {
            header("Authorization", "Bearer ${adminToken()}")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(request)
        }.andExpect { status { isCreated() } }
    }

    @Test
    fun `create rejects a blank name`() {
        val invalid = sampleRequest().copy(name = "")

        mockMvc.post("/api/admin/item-templates") {
            header("Authorization", "Bearer ${adminToken()}")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(invalid)
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `delete succeeds for an admin`() {
        val id = UUID.randomUUID()
        every { service.delete(id) } just Runs

        mockMvc.delete("/api/admin/item-templates/$id") { header("Authorization", "Bearer ${adminToken()}") }
            .andExpect { status { isNoContent() } }
    }
}
