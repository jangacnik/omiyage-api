package com.omiyage.backend.web

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
import com.omiyage.backend.catalog.AdminCategoryController
import com.omiyage.backend.catalog.CategoryDto
import com.omiyage.backend.catalog.CategoryService
import com.omiyage.backend.catalog.CreateCategoryRequest
import com.omiyage.backend.config.MeishiProperties
import com.omiyage.backend.security.JwtAuthenticationFilter
import com.omiyage.backend.security.JwtVerifier
import com.omiyage.backend.security.SecurityConfig
import com.omiyage.backend.security.TestJwt
import io.mockk.every
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import java.util.UUID

private const val TEST_SECRET = "admin-audit-config-secret"

@WebMvcTest(controllers = [AdminCategoryController::class])
@Import(SecurityConfig::class, JwtAuthenticationFilter::class, JwtVerifier::class, AdminAuditConfig::class)
@EnableConfigurationProperties(MeishiProperties::class)
@TestPropertySource(properties = ["omiyage.meishi.jwt-secret=$TEST_SECRET", "omiyage.meishi.api-url=http://localhost:8080"])
class AdminAuditConfigTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @MockkBean
    lateinit var categories: CategoryService

    private val appender = ListAppender<ILoggingEvent>()
    private val auditLogger = LoggerFactory.getLogger("audit") as Logger
    private var originalLevel: Level? = null

    @BeforeEach
    fun attachAppender() {
        originalLevel = auditLogger.level
        auditLogger.level = Level.INFO
        appender.start()
        auditLogger.addAppender(appender)
    }

    @AfterEach
    fun detachAppender() {
        auditLogger.detachAppender(appender)
        auditLogger.level = originalLevel
    }

    @Test
    fun `an admin write under the admin path logs one audit event`() {
        val adminId = UUID.randomUUID()
        val token = TestJwt.sign(TEST_SECRET, subject = adminId.toString(), role = "ADMIN")
        every { categories.create("Snacks", "snacks") } returns CategoryDto(UUID.randomUUID(), "Snacks", "snacks")

        mockMvc.post("/api/admin/categories") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(CreateCategoryRequest("Snacks", "snacks"))
        }.andExpect { status { isCreated() } }

        assertEquals(
            listOf("admin_mutation user=$adminId method=POST path=/api/admin/categories status=201"),
            appender.list.map { it.formattedMessage },
        )
    }
}
