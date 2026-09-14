package com.omiyage.backend.dashboard

import com.omiyage.backend.config.MeishiProperties
import com.omiyage.backend.security.JwtAuthenticationFilter
import com.omiyage.backend.security.JwtVerifier
import com.omiyage.backend.security.SecurityConfig
import com.omiyage.backend.security.TestJwt
import com.ninjasquad.springmockk.MockkBean
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

private const val TEST_SECRET = "admin-dashboard-controller-secret"

@WebMvcTest(controllers = [AdminDashboardController::class])
@Import(SecurityConfig::class, JwtAuthenticationFilter::class, JwtVerifier::class)
@EnableConfigurationProperties(MeishiProperties::class)
@TestPropertySource(properties = ["omiyage.meishi.jwt-secret=$TEST_SECRET", "omiyage.meishi.api-url=http://localhost:8080"])
class AdminDashboardControllerTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @MockkBean
    lateinit var service: DashboardService

    private fun userToken() = TestJwt.sign(TEST_SECRET, role = "USER")
    private fun adminToken() = TestJwt.sign(TEST_SECRET, role = "ADMIN")

    @Test
    fun `userDashboard is rejected for a non-admin user`() {
        mockMvc.get("/api/admin/dashboard/users") { header("Authorization", "Bearer ${userToken()}") }
            .andExpect { status { isForbidden() } }
    }

    @Test
    fun `userDashboard succeeds for an admin`() {
        every { service.userDashboard(any()) } returns emptyList()

        mockMvc.get("/api/admin/dashboard/users") { header("Authorization", "Bearer ${adminToken()}") }
            .andExpect { status { isOk() } }
    }

    @Test
    fun `deliveredOrders is rejected for a non-admin user`() {
        mockMvc.get("/api/admin/dashboard/users/${UUID.randomUUID()}/delivered-orders") {
            header("Authorization", "Bearer ${userToken()}")
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `deliveredOrders succeeds for an admin`() {
        val userId = UUID.randomUUID()
        every { service.deliveredOrdersForUser(userId) } returns emptyList()

        mockMvc.get("/api/admin/dashboard/users/$userId/delivered-orders") {
            header("Authorization", "Bearer ${adminToken()}")
        }.andExpect { status { isOk() } }
    }
}
