package com.omiyage.backend.web

import com.omiyage.backend.PostgresIntegrationTest
import com.omiyage.backend.logging.LogCapture
import com.omiyage.backend.security.TestJwt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
class AdminAuditIntegrationTest : PostgresIntegrationTest() {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `an admin write that fails is audited with the status the client got`() {
        val adminId = UUID.randomUUID()
        val token = TestJwt.sign("integration-test-secret", subject = adminId.toString(), nickname = "root", role = "ADMIN")

        LogCapture("audit").use { capture ->
            mockMvc.perform(delete("/api/admin/orders/${UUID.randomUUID()}").header("Authorization", "Bearer $token"))
                .andExpect(status().isNotFound)

            assertEquals(
                "admin_mutation user=$adminId method=DELETE route=/api/admin/orders/{id} status=404",
                capture.events.single().formattedMessage,
            )
        }
    }
}
