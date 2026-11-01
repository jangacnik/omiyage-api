package com.omiyage.backend.shipment

import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
import com.omiyage.backend.config.MeishiProperties
import com.omiyage.backend.order.MarkItemPaidRequest
import com.omiyage.backend.order.OrderDto
import com.omiyage.backend.order.OrderStatus
import com.omiyage.backend.security.JwtAuthenticationFilter
import com.omiyage.backend.security.JwtVerifier
import com.omiyage.backend.security.SecurityConfig
import com.omiyage.backend.security.TestJwt
import com.omiyage.backend.user.MeishiNicknameClient
import com.omiyage.backend.user.NicknameDto
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

private const val TEST_SECRET = "shipment-controllers-secret"

@WebMvcTest(controllers = [MemberShipmentController::class, AdminShipmentController::class, BrowsableShipmentController::class])
@Import(SecurityConfig::class, JwtAuthenticationFilter::class, JwtVerifier::class)
@EnableConfigurationProperties(MeishiProperties::class)
@TestPropertySource(properties = ["omiyage.meishi.jwt-secret=$TEST_SECRET", "omiyage.meishi.api-url=http://localhost:8080"])
class ShipmentControllersTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @MockkBean
    lateinit var service: ShipmentService

    @MockkBean
    lateinit var nicknames: MeishiNicknameClient

    private val userId = UUID.randomUUID()
    private fun userToken() = TestJwt.sign(TEST_SECRET, subject = userId.toString(), role = "USER")
    private fun adminToken() = TestJwt.sign(TEST_SECRET, role = "ADMIN")
    private fun bearer(token: String) = "Bearer $token"

    private fun orderOf(owner: UUID) = OrderDto(
        id = UUID.randomUUID(),
        userId = owner,
        status = OrderStatus.IN_PROGRESS,
        open = true,
        notes = null,
        nickname = null,
        shipmentId = null,
        createdAt = OffsetDateTime.now(),
        updatedAt = OffsetDateTime.now(),
        items = emptyList(),
    )

    private fun shipmentOf(vararg owners: UUID, title: String? = "Box") = ShipmentDto(
        id = UUID.randomUUID(),
        status = ShipmentStatus.PREPARING,
        title = title,
        destination = null,
        shippingMethod = null,
        trackingNumber = null,
        notes = null,
        ownerUserId = owners.firstOrNull(),
        shippedAt = null,
        arrivedAt = null,
        totalWeightGrams = BigDecimal("250"),
        shippingCostJpy = null,
        shippingCostEur = null,
        estimatedDays = null,
        createdAt = OffsetDateTime.now(),
        updatedAt = OffsetDateTime.now(),
        orders = owners.map { orderOf(it) },
        profit = BigDecimal.ZERO,
    )

    // --- member: /api/shipments/mine ---

    @Test
    fun `mine list requires authentication`() {
        mockMvc.get("/api/shipments/mine").andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `mine list returns only shipments containing the caller's orders, with nicknames`() {
        val mine = shipmentOf(userId, title = "Mine")
        val others = shipmentOf(UUID.randomUUID(), title = "Others")
        every { service.listAll() } returns listOf(mine, others)
        every { nicknames.nicknames(any()) } returns listOf(NicknameDto(userId, "bob"))

        mockMvc.get("/api/shipments/mine") { header("Authorization", bearer(userToken())) }
            .andExpect {
                status { isOk() }
                jsonPath("$.length()") { value(1) }
                jsonPath("$[0].title") { value("Mine") }
                jsonPath("$[0].members[0].nickname") { value("bob") }
            }
    }

    @Test
    fun `mine list still works when the nickname directory is unreachable`() {
        every { service.listAll() } returns listOf(shipmentOf(userId))
        every { nicknames.nicknames(any()) } throws IllegalStateException("meishi down")

        mockMvc.get("/api/shipments/mine") { header("Authorization", bearer(userToken())) }
            .andExpect {
                status { isOk() }
                jsonPath("$[0].members[0].nickname") { doesNotExist() }
            }
    }

    @Test
    fun `mine get returns a shipment the caller belongs to`() {
        val shipment = shipmentOf(userId)
        every { service.getById(shipment.id) } returns shipment
        every { nicknames.nicknames(any()) } returns emptyList()

        mockMvc.get("/api/shipments/mine/${shipment.id}") { header("Authorization", bearer(userToken())) }
            .andExpect {
                status { isOk() }
                jsonPath("$.id") { value(shipment.id.toString()) }
                jsonPath("$.status") { value("PREPARING") }
            }
    }

    @Test
    fun `mine get returns 404 for a shipment the caller does not belong to`() {
        val shipment = shipmentOf(UUID.randomUUID())
        every { service.getById(shipment.id) } returns shipment
        every { nicknames.nicknames(any()) } returns emptyList()

        mockMvc.get("/api/shipments/mine/${shipment.id}") { header("Authorization", bearer(userToken())) }
            .andExpect { status { isNotFound() } }
    }

    @Test
    fun `mine rename passes the caller id and title to the service`() {
        val shipment = shipmentOf(userId, title = "New name")
        every { service.renameMine(shipment.id, userId, "New name") } returns shipment
        every { nicknames.nicknames(any()) } returns emptyList()

        mockMvc.patch("/api/shipments/mine/${shipment.id}/title") {
            header("Authorization", bearer(userToken()))
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(RenameShipmentRequest("New name"))
        }.andExpect {
            status { isOk() }
            jsonPath("$.title") { value("New name") }
        }
    }

    @Test
    fun `mine rename rejects a title over 100 characters`() {
        mockMvc.patch("/api/shipments/mine/${UUID.randomUUID()}/title") {
            header("Authorization", bearer(userToken()))
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(RenameShipmentRequest("x".repeat(101)))
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `mine shipping update passes destination and method to the service`() {
        val shipment = shipmentOf(userId).copy(destination = ShipmentDestination.AUSTRIA, shippingMethod = ShippingMethod.EMS)
        every { service.updateShippingMine(shipment.id, userId, ShipmentDestination.AUSTRIA, ShippingMethod.EMS) } returns shipment
        every { nicknames.nicknames(any()) } returns emptyList()

        mockMvc.patch("/api/shipments/mine/${shipment.id}/shipping") {
            header("Authorization", bearer(userToken()))
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(UpdateShippingRequest(ShipmentDestination.AUSTRIA, ShippingMethod.EMS))
        }.andExpect {
            status { isOk() }
            jsonPath("$.destination") { value("AUSTRIA") }
            jsonPath("$.shippingMethod") { value("EMS") }
        }
    }

    @Test
    fun `mine mark-arrived delegates with the caller id`() {
        val shipment = shipmentOf(userId)
        every { service.markArrivedMine(shipment.id, userId) } returns shipment
        every { nicknames.nicknames(any()) } returns emptyList()

        mockMvc.post("/api/shipments/mine/${shipment.id}/mark-arrived") { header("Authorization", bearer(userToken())) }
            .andExpect { status { isOk() } }

        verify(exactly = 1) { service.markArrivedMine(shipment.id, userId) }
    }

    // --- member: /api/shipments/browsable ---

    @Test
    fun `browsable requires authentication`() {
        mockMvc.get("/api/shipments/browsable").andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `browsable returns other members' shipments for the caller`() {
        val dto = BrowsableShipmentDto(UUID.randomUUID(), "Theirs", 4, BigDecimal("900"), "alice")
        every { service.browsableForMember(userId, bearer(userToken())) } returns listOf(dto)

        mockMvc.get("/api/shipments/browsable") { header("Authorization", bearer(userToken())) }
            .andExpect {
                status { isOk() }
                jsonPath("$[0].title") { value("Theirs") }
                jsonPath("$[0].itemCount") { value(4) }
                jsonPath("$[0].ownerNickname") { value("alice") }
            }
    }

    // --- admin: /api/admin/shipments ---

    @Test
    fun `admin list requires authentication`() {
        mockMvc.get("/api/admin/shipments").andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `admin list is forbidden for a non-admin user`() {
        mockMvc.get("/api/admin/shipments") { header("Authorization", bearer(userToken())) }
            .andExpect { status { isForbidden() } }
    }

    @Test
    fun `admin list, delivered and get return shipments`() {
        val shipment = shipmentOf(userId)
        every { service.listActive() } returns listOf(shipment)
        every { service.listDelivered() } returns listOf(shipment, shipment)
        every { service.getById(shipment.id) } returns shipment

        mockMvc.get("/api/admin/shipments") { header("Authorization", bearer(adminToken())) }
            .andExpect { status { isOk() }; jsonPath("$.length()") { value(1) } }
        mockMvc.get("/api/admin/shipments/delivered") { header("Authorization", bearer(adminToken())) }
            .andExpect { status { isOk() }; jsonPath("$.length()") { value(2) } }
        mockMvc.get("/api/admin/shipments/${shipment.id}") { header("Authorization", bearer(adminToken())) }
            .andExpect { status { isOk() }; jsonPath("$.totalWeightGrams") { value(250) } }
    }

    @Test
    fun `admin create returns 201`() {
        val request = CreateShipmentRequest(orderIds = listOf(UUID.randomUUID()), title = "New")
        every { service.create(request) } returns shipmentOf(userId, title = "New")

        mockMvc.post("/api/admin/shipments") {
            header("Authorization", bearer(adminToken()))
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(request)
        }.andExpect {
            status { isCreated() }
            jsonPath("$.title") { value("New") }
        }
    }

    @Test
    fun `admin create rejects an empty order list`() {
        mockMvc.post("/api/admin/shipments") {
            header("Authorization", bearer(adminToken()))
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(CreateShipmentRequest(orderIds = emptyList()))
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `admin update returns the updated shipment`() {
        val shipment = shipmentOf(userId).copy(status = ShipmentStatus.SHIPPED, trackingNumber = "EE123")
        val request = UpdateShipmentRequest(status = ShipmentStatus.SHIPPED, trackingNumber = "EE123")
        every { service.update(shipment.id, request) } returns shipment

        mockMvc.patch("/api/admin/shipments/${shipment.id}") {
            header("Authorization", bearer(adminToken()))
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(request)
        }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("SHIPPED") }
            jsonPath("$.trackingNumber") { value("EE123") }
        }
    }

    @Test
    fun `admin delete returns 204 and delegates`() {
        val id = UUID.randomUUID()
        every { service.adminDelete(id) } just Runs

        mockMvc.delete("/api/admin/shipments/$id") { header("Authorization", bearer(adminToken())) }
            .andExpect { status { isNoContent() } }

        verify(exactly = 1) { service.adminDelete(id) }
    }

    @Test
    fun `admin combine merges the given shipments`() {
        val ids = listOf(UUID.randomUUID(), UUID.randomUUID())
        every { service.combineShipments(ids, "Merged") } returns shipmentOf(userId, title = "Merged")

        mockMvc.post("/api/admin/shipments/combine") {
            header("Authorization", bearer(adminToken()))
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(CombineShipmentsRequest(ids, "Merged"))
        }.andExpect {
            status { isOk() }
            jsonPath("$.title") { value("Merged") }
        }
    }

    @Test
    fun `admin split returns 201 with the new shipment`() {
        val sourceId = UUID.randomUUID()
        val orderIds = listOf(UUID.randomUUID())
        every { service.splitOff(sourceId, orderIds, "Half") } returns shipmentOf(userId, title = "Half")

        mockMvc.post("/api/admin/shipments/$sourceId/split") {
            header("Authorization", bearer(adminToken()))
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(SplitShipmentRequest(orderIds, "Half"))
        }.andExpect {
            status { isCreated() }
            jsonPath("$.title") { value("Half") }
        }
    }

    @Test
    fun `admin markPaid passes the paid flag`() {
        val id = UUID.randomUUID()
        every { service.adminMarkPaid(id, true) } returns shipmentOf(userId)

        mockMvc.patch("/api/admin/shipments/$id/paid") {
            header("Authorization", bearer(adminToken()))
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(MarkItemPaidRequest(true))
        }.andExpect { status { isOk() } }

        verify(exactly = 1) { service.adminMarkPaid(id, true) }
    }
}
