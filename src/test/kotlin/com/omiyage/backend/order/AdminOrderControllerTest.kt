package com.omiyage.backend.order

import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
import com.omiyage.backend.config.MeishiProperties
import com.omiyage.backend.security.JwtAuthenticationFilter
import com.omiyage.backend.security.JwtVerifier
import com.omiyage.backend.security.SecurityConfig
import com.omiyage.backend.security.TestJwt
import com.omiyage.backend.shipment.AssignShipmentRequest
import com.omiyage.backend.shipment.ShipmentService
import com.omiyage.backend.web.PageResponse
import io.mockk.every
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import com.omiyage.backend.common.Condition
import com.omiyage.backend.common.Currency
import java.time.OffsetDateTime
import java.util.UUID

private const val TEST_SECRET = "admin-order-controller-secret"

@WebMvcTest(controllers = [AdminOrderController::class])
@Import(SecurityConfig::class, JwtAuthenticationFilter::class, JwtVerifier::class)
@EnableConfigurationProperties(MeishiProperties::class)
@TestPropertySource(properties = ["omiyage.meishi.jwt-secret=$TEST_SECRET", "omiyage.meishi.api-url=http://localhost:8080"])
class AdminOrderControllerTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @MockkBean
    lateinit var service: OrderService

    @MockkBean
    lateinit var shipments: ShipmentService

    private fun userToken() = TestJwt.sign(TEST_SECRET, role = "USER")
    private fun adminToken() = TestJwt.sign(TEST_SECRET, role = "ADMIN")

    private fun sampleOrder(status: OrderStatus = OrderStatus.IN_PROGRESS, shipmentId: UUID? = null) = OrderDto(
        id = UUID.randomUUID(),
        userId = UUID.randomUUID(),
        status = status,
        open = status !in setOf(OrderStatus.SENT, OrderStatus.DELIVERED, OrderStatus.CANCELLED),
        notes = null,
        nickname = null,
        shipmentId = shipmentId,
        createdAt = OffsetDateTime.now(),
        updatedAt = OffsetDateTime.now(),
        items = emptyList(),
    )

    @Test
    fun `create for a user is rejected for a non-admin user`() {
        val userId = UUID.randomUUID()
        mockMvc.post("/api/admin/orders/for-user/$userId") {
            header("Authorization", "Bearer ${userToken()}")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(
                CreateOrderRequest(items = listOf(CreateOrderItemRequest(title = "A", condition = Condition.NEW, currency = Currency.JPY))),
            )
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `create for a user succeeds for an admin`() {
        val userId = UUID.randomUUID()
        val request = CreateOrderRequest(items = listOf(CreateOrderItemRequest(title = "A", condition = Condition.NEW, currency = Currency.JPY)))
        every { service.adminCreate(userId, request) } returns sampleOrder().copy(userId = userId)

        mockMvc.post("/api/admin/orders/for-user/$userId") {
            header("Authorization", "Bearer ${adminToken()}")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(request)
        }.andExpect {
            status { isCreated() }
            jsonPath("$.userId") { value(userId.toString()) }
        }
    }

    @Test
    fun `list is rejected for a non-admin user`() {
        mockMvc.get("/api/admin/orders") { header("Authorization", "Bearer ${userToken()}") }
            .andExpect { status { isForbidden() } }
    }

    @Test
    fun `list returns all orders for an admin`() {
        every { service.adminList(null, any(), any(), any(), any()) } returns PageResponse(listOf(sampleOrder()), 0, 20, 1, 1)

        mockMvc.get("/api/admin/orders") { header("Authorization", "Bearer ${adminToken()}") }
            .andExpect { status { isOk() } }
    }

    @Test
    fun `updateStatus is rejected for a non-admin user`() {
        mockMvc.patch("/api/admin/orders/${UUID.randomUUID()}") {
            header("Authorization", "Bearer ${userToken()}")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(UpdateOrderStatusRequest(OrderStatus.SENT))
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `updateStatus succeeds for an admin`() {
        val id = UUID.randomUUID()
        every { service.updateStatus(id, OrderStatus.SENT) } returns sampleOrder(OrderStatus.SENT)

        mockMvc.patch("/api/admin/orders/$id") {
            header("Authorization", "Bearer ${adminToken()}")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(UpdateOrderStatusRequest(OrderStatus.SENT))
        }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("SENT") }
        }
    }

    @Test
    fun `updateItem changes an item's status`() {
        val orderId = UUID.randomUUID()
        val itemId = UUID.randomUUID()
        every {
            service.updateItem(orderId, itemId, OrderItemStatus.BOUGHT, java.math.BigDecimal("999.00"))
        } returns sampleOrder()

        mockMvc.patch("/api/admin/orders/$orderId/items/$itemId") {
            header("Authorization", "Bearer ${adminToken()}")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(
                UpdateOrderItemRequest(status = OrderItemStatus.BOUGHT, actualPrice = java.math.BigDecimal("999.00")),
            )
        }.andExpect { status { isOk() } }
    }

    @Test
    fun `markItemPaid is rejected for a non-admin user`() {
        mockMvc.patch("/api/admin/orders/${UUID.randomUUID()}/items/${UUID.randomUUID()}/paid") {
            header("Authorization", "Bearer ${userToken()}")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(MarkItemPaidRequest(true))
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `markItemPaid succeeds for an admin`() {
        val orderId = UUID.randomUUID()
        val itemId = UUID.randomUUID()
        every { service.adminMarkItemPaid(orderId, itemId, true) } returns sampleOrder()

        mockMvc.patch("/api/admin/orders/$orderId/items/$itemId/paid") {
            header("Authorization", "Bearer ${adminToken()}")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(MarkItemPaidRequest(true))
        }.andExpect { status { isOk() } }
    }

    @Test
    fun `assignShipment attaches an order to a shipment`() {
        val orderId = UUID.randomUUID()
        val shipmentId = UUID.randomUUID()
        every { shipments.assignOrder(orderId, shipmentId) } returns sampleOrder(shipmentId = shipmentId)

        mockMvc.patch("/api/admin/orders/$orderId/shipment") {
            header("Authorization", "Bearer ${adminToken()}")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(AssignShipmentRequest(shipmentId))
        }.andExpect {
            status { isOk() }
            jsonPath("$.shipmentId") { value(shipmentId.toString()) }
        }
    }

    @Test
    fun `merge is rejected for a non-admin user`() {
        mockMvc.post("/api/admin/orders/merge") {
            header("Authorization", "Bearer ${userToken()}")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(MergeOrdersRequest(listOf(UUID.randomUUID(), UUID.randomUUID())))
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `merge succeeds for an admin`() {
        val ids = listOf(UUID.randomUUID(), UUID.randomUUID())
        every { service.adminMerge(ids) } returns sampleOrder()

        mockMvc.post("/api/admin/orders/merge") {
            header("Authorization", "Bearer ${adminToken()}")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(MergeOrdersRequest(ids))
        }.andExpect { status { isOk() } }
    }

    @Test
    fun `updateNickname is rejected for a non-admin user`() {
        mockMvc.patch("/api/admin/orders/${UUID.randomUUID()}/nickname") {
            header("Authorization", "Bearer ${userToken()}")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(UpdateOrderNicknameRequest("Grandma's Gifts"))
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `updateNickname succeeds for an admin, for any user's order`() {
        val id = UUID.randomUUID()
        every { service.adminUpdateNickname(id, "Grandma's Gifts") } returns sampleOrder().copy(nickname = "Grandma's Gifts")

        mockMvc.patch("/api/admin/orders/$id/nickname") {
            header("Authorization", "Bearer ${adminToken()}")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(UpdateOrderNicknameRequest("Grandma's Gifts"))
        }.andExpect {
            status { isOk() }
            jsonPath("$.nickname") { value("Grandma's Gifts") }
        }
    }

    @Test
    fun `moveItems is rejected for a non-admin user`() {
        mockMvc.post("/api/admin/orders/${UUID.randomUUID()}/items/move") {
            header("Authorization", "Bearer ${userToken()}")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(MoveOrderItemsRequest(listOf(UUID.randomUUID())))
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `moveItems succeeds for an admin`() {
        val sourceId = UUID.randomUUID()
        val itemId = UUID.randomUUID()
        val targetId = UUID.randomUUID()
        every { service.adminMoveItems(sourceId, listOf(itemId), targetId) } returns
            MoveItemsResponseDto(source = sampleOrder(), target = sampleOrder())

        mockMvc.post("/api/admin/orders/$sourceId/items/move") {
            header("Authorization", "Bearer ${adminToken()}")
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(MoveOrderItemsRequest(listOf(itemId), targetId))
        }.andExpect { status { isOk() } }
    }

    @Test
    fun `listForUser is rejected for a non-admin user`() {
        mockMvc.get("/api/admin/orders/by-user/${UUID.randomUUID()}") {
            header("Authorization", "Bearer ${userToken()}")
        }.andExpect { status { isForbidden() } }
    }
}
