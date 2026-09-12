package com.omiyage.backend.catalog

import com.ninjasquad.springmockk.MockkBean
import com.omiyage.backend.common.Condition
import com.omiyage.backend.common.Currency
import com.omiyage.backend.config.MeishiProperties
import com.omiyage.backend.security.JwtAuthenticationFilter
import com.omiyage.backend.security.JwtVerifier
import com.omiyage.backend.security.SecurityConfig
import com.omiyage.backend.security.TestJwt
import com.omiyage.backend.web.PageResponse
import io.mockk.every
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.math.BigDecimal
import java.util.UUID

private const val TEST_SECRET = "inventory-controller-secret"

@WebMvcTest(controllers = [InventoryController::class])
@Import(SecurityConfig::class, JwtAuthenticationFilter::class, JwtVerifier::class)
@EnableConfigurationProperties(MeishiProperties::class)
@TestPropertySource(properties = ["omiyage.meishi.jwt-secret=$TEST_SECRET", "omiyage.meishi.api-url=http://localhost:8080"])
class InventoryControllerTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @MockkBean
    lateinit var service: InventoryService

    private fun token() = TestJwt.sign(TEST_SECRET, role = "USER")

    private fun sampleItem() = InventoryItemDto(
        id = UUID.randomUUID(),
        title = "Kit Kat matcha 12-pack",
        description = "Sealed box",
        link = null,
        condition = Condition.NEW,
        priceAmount = BigDecimal("1200.00"),
        currency = Currency.JPY,
        stockQuantity = 3,
        status = InventoryStatus.ACTIVE,
        images = listOf("items/kitkat-1.jpg"),
        categories = listOf(CategoryDto(UUID.randomUUID(), "Snacks", "snacks")),
    )

    @Test
    fun `search requires authentication`() {
        mockMvc.get("/api/inventory").andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `search only ever asks the service for ACTIVE items`() {
        every { service.search(null, null, InventoryStatus.ACTIVE, any()) } returns
            PageResponse(listOf(sampleItem()), 0, 20, 1, 1)

        mockMvc.get("/api/inventory") { header("Authorization", "Bearer ${token()}") }
            .andExpect {
                status { isOk() }
                jsonPath("$.content[0].title") { value("Kit Kat matcha 12-pack") }
                jsonPath("$.totalElements") { value(1) }
            }
    }

    @Test
    fun `search forwards title and categoryId query params`() {
        val categoryId = UUID.randomUUID()
        every { service.search("kitkat", categoryId, InventoryStatus.ACTIVE, any()) } returns
            PageResponse(emptyList(), 0, 20, 0, 0)

        mockMvc.get("/api/inventory") {
            header("Authorization", "Bearer ${token()}")
            param("title", "kitkat")
            param("categoryId", categoryId.toString())
        }.andExpect { status { isOk() } }
    }

    @Test
    fun `getById returns the item`() {
        val item = sampleItem()
        every { service.getById(item.id) } returns item

        mockMvc.get("/api/inventory/${item.id}") { header("Authorization", "Bearer ${token()}") }
            .andExpect {
                status { isOk() }
                jsonPath("$.id") { value(item.id.toString()) }
            }
    }
}
