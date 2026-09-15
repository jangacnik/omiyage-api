package com.omiyage.backend.catalog

import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
import com.omiyage.backend.common.Condition
import com.omiyage.backend.common.Currency
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
import org.springframework.test.web.servlet.put
import java.math.BigDecimal
import java.util.UUID

private const val TEST_SECRET = "admin-inventory-controller-secret"

@WebMvcTest(controllers = [AdminInventoryController::class])
@Import(SecurityConfig::class, JwtAuthenticationFilter::class, JwtVerifier::class)
@EnableConfigurationProperties(MeishiProperties::class)
@TestPropertySource(properties = ["omiyage.meishi.jwt-secret=$TEST_SECRET", "omiyage.meishi.api-url=http://localhost:8080"])
class AdminInventoryControllerTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @MockkBean
    lateinit var service: InventoryService

    private fun userToken() = TestJwt.sign(TEST_SECRET, role = "USER")
    private fun adminToken() = TestJwt.sign(TEST_SECRET, role = "ADMIN")

    private fun request() = InventoryItemRequest(
        title = "Kit Kat matcha 12-pack",
        description = "Sealed box",
        condition = Condition.NEW,
        priceAmount = BigDecimal("1200.00"),
        currency = Currency.JPY,
        stockQuantity = 3,
    )

    private fun dto(req: InventoryItemRequest) = InventoryItemDto(
        id = UUID.randomUUID(),
        title = req.title,
        description = req.description,
        link = req.link,
        condition = req.condition,
        priceAmount = req.priceAmount,
        currency = req.currency,
        stockQuantity = req.stockQuantity,
        status = InventoryStatus.ACTIVE,
        images = emptyList(),
        categories = emptyList(),
    )

    @Test
    fun `create is rejected for a non-admin user`() {
        mockMvc.post("/api/admin/inventory") {
            header("Authorization", "Bearer ${userToken()}")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(request())
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `create succeeds for an admin`() {
        val req = request()
        every { service.create(req) } returns dto(req)

        mockMvc.post("/api/admin/inventory") {
            header("Authorization", "Bearer ${adminToken()}")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(req)
        }.andExpect {
            status { isCreated() }
            jsonPath("$.title") { value(req.title) }
        }
    }

    @Test
    fun `create rejects a blank title`() {
        val invalid = request().copy(title = "")

        mockMvc.post("/api/admin/inventory") {
            header("Authorization", "Bearer ${adminToken()}")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(invalid)
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `create rejects more than 5 images`() {
        val invalid = request().copy(imageKeys = List(6) { "img-$it" })

        mockMvc.post("/api/admin/inventory") {
            header("Authorization", "Bearer ${adminToken()}")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(invalid)
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `update succeeds for an admin`() {
        val id = UUID.randomUUID()
        val req = request()
        every { service.update(id, req) } returns dto(req)

        mockMvc.put("/api/admin/inventory/$id") {
            header("Authorization", "Bearer ${adminToken()}")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(req)
        }.andExpect { status { isOk() } }
    }

    @Test
    fun `delete is rejected for a non-admin user`() {
        mockMvc.delete("/api/admin/inventory/${UUID.randomUUID()}") {
            header("Authorization", "Bearer ${userToken()}")
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `delete succeeds for an admin`() {
        val id = UUID.randomUUID()
        every { service.delete(id) } just Runs

        mockMvc.delete("/api/admin/inventory/$id") {
            header("Authorization", "Bearer ${adminToken()}")
        }.andExpect { status { isNoContent() } }
    }
}
