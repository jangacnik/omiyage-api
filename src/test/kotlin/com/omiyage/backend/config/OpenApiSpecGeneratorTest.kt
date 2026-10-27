package com.omiyage.backend.config

import com.ninjasquad.springmockk.MockkBean
import com.omiyage.backend.auth.AuthService
import com.omiyage.backend.auth.LoginRateLimiter
import com.omiyage.backend.catalog.CategoryService
import com.omiyage.backend.catalog.InventoryService
import com.omiyage.backend.catalog.ItemTemplateService
import com.omiyage.backend.combine.CombineRequestService
import com.omiyage.backend.dashboard.DashboardService
import com.omiyage.backend.media.MediaService
import com.omiyage.backend.order.OrderService
import com.omiyage.backend.security.JwtAuthenticationFilter
import com.omiyage.backend.security.JwtVerifier
import com.omiyage.backend.security.SecurityConfig
import com.omiyage.backend.shipment.ExchangeRateService
import com.omiyage.backend.shipment.ShipmentService
import com.omiyage.backend.shipment.ShippingRateService
import com.omiyage.backend.user.MeishiNicknameClient
import com.omiyage.backend.user.MeishiProfileClient
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.fail
import org.springdoc.core.configuration.SpringDocConfiguration
import org.springdoc.core.properties.SpringDocConfigProperties
import org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.nio.file.Files
import java.nio.file.Path

private const val SPEC_PATH = "docs/openapi.json"

@WebMvcTest
@AutoConfigureMockMvc(addFilters = false)
@ImportAutoConfiguration(SpringDocConfiguration::class, SpringDocWebMvcConfiguration::class, SpringDocConfigProperties::class)
@Import(OpenApiConfig::class, SecurityConfig::class, JwtAuthenticationFilter::class, JwtVerifier::class)
@EnableConfigurationProperties(MeishiProperties::class)
@TestPropertySource(
    properties = [
        "omiyage.meishi.jwt-secret=openapi-generator-secret",
        "omiyage.meishi.api-url=http://localhost:8080",
        "springdoc.api-docs.enabled=true",
        "springdoc.writer-with-default-pretty-printer=true",
        "springdoc.writer-with-order-by-keys=true",
    ],
)
class OpenApiSpecGeneratorTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @MockkBean lateinit var authService: AuthService
    @MockkBean lateinit var loginRateLimiter: LoginRateLimiter
    @MockkBean lateinit var categoryService: CategoryService
    @MockkBean lateinit var inventoryService: InventoryService
    @MockkBean lateinit var itemTemplateService: ItemTemplateService
    @MockkBean lateinit var combineRequestService: CombineRequestService
    @MockkBean lateinit var dashboardService: DashboardService
    @MockkBean lateinit var mediaService: MediaService
    @MockkBean lateinit var orderService: OrderService
    @MockkBean lateinit var shipmentService: ShipmentService
    @MockkBean lateinit var exchangeRateService: ExchangeRateService
    @MockkBean lateinit var shippingRateService: ShippingRateService
    @MockkBean lateinit var meishiNicknameClient: MeishiNicknameClient
    @MockkBean lateinit var meishiProfileClient: MeishiProfileClient

    @Test
    fun `committed OpenAPI spec matches the generated spec`() {
        val generated = mockMvc.get("/v3/api-docs").andReturn().response.contentAsString + "\n"
        val path = Path.of(SPEC_PATH)
        val committed = if (Files.exists(path)) Files.readString(path) else null

        if (committed != generated) {
            Files.createDirectories(path.parent)
            Files.writeString(path, generated)
            println("OPENAPI-BEGIN\n$generated\nOPENAPI-END")
            fail("$SPEC_PATH was out of date and has been regenerated. Commit it:\nOPENAPI-BEGIN\n$generated\nOPENAPI-END")
        }
    }
}
