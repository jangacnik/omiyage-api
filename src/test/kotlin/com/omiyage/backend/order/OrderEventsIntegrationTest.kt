package com.omiyage.backend.order

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.omiyage.backend.PostgresIntegrationTest
import com.omiyage.backend.catalog.InventoryItemRequest
import com.omiyage.backend.catalog.InventoryService
import com.omiyage.backend.common.Condition
import com.omiyage.backend.common.Currency
import com.omiyage.backend.security.MeishiPrincipal
import com.omiyage.backend.security.Role
import com.omiyage.backend.shipment.ShipmentService
import com.omiyage.backend.shipment.ShipmentStatus
import com.omiyage.backend.shipment.UpdateShipmentRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.util.UUID

private const val SECRET = "integration-webhook-secret"

/** Not @Transactional: events are only sent after a real commit. */
@SpringBootTest
class OrderEventsIntegrationTest : PostgresIntegrationTest() {

    companion object {
        private val receiver = WebhookReceiver()

        @JvmStatic
        @DynamicPropertySource
        fun webhookProperties(registry: DynamicPropertyRegistry) {
            registry.add("omiyage.order-webhook.url") { receiver.url }
            registry.add("omiyage.order-webhook.secret") { SECRET }
        }
    }

    @Autowired
    lateinit var orderService: OrderService

    @Autowired
    lateinit var inventoryService: InventoryService

    @Autowired
    lateinit var shipmentService: ShipmentService

    @Autowired
    lateinit var objectMapper: ObjectMapper

    private val owner = MeishiPrincipal(UUID.randomUUID(), "bob", Role.USER)

    private fun nextEvent(): JsonNode {
        val received = receiver.next()
        assertThat(received.signature).isEqualTo(signWebhook(SECRET, received.timestamp!!.toLong(), received.body))
        return objectMapper.readTree(received.body)
    }

    private fun customOrder(): OrderDto = orderService.create(
        owner,
        CreateOrderRequest(items = listOf(CreateOrderItemRequest(title = "Custom ${UUID.randomUUID()}", condition = Condition.NEW, currency = Currency.EUR))),
    )

    @Test
    fun `order created carries reference, items and total`() {
        val catalog = inventoryService.create(
            InventoryItemRequest(
                title = "Catalog ${UUID.randomUUID()}",
                description = null,
                condition = Condition.NEW,
                priceAmount = BigDecimal("7.25"),
                currency = Currency.EUR,
                stockQuantity = 5,
            ),
        )
        val customTitle = "Custom ${UUID.randomUUID()}"

        val order = orderService.create(
            owner,
            CreateOrderRequest(
                nickname = "Birthday",
                items = listOf(
                    CreateOrderItemRequest(inventoryItemId = catalog.id, currency = Currency.EUR, quantity = 2),
                    CreateOrderItemRequest(title = customTitle, condition = Condition.NEW, currency = Currency.EUR, maxPrice = BigDecimal("12.5")),
                ),
            ),
        )

        val event = nextEvent()
        assertThat(event["type"].asText()).isEqualTo(ORDER_CREATED)
        assertThat(UUID.fromString(event["eventId"].asText())).isNotNull()
        assertThat(event["occurredAt"].asText()).endsWith("Z")
        val payload = event["order"]
        assertThat(payload["id"].asText()).isEqualTo(order.id.toString())
        assertThat(payload["reference"].asText()).isEqualTo(order.id.toString().take(8))
        assertThat(payload["userId"].asText()).isEqualTo(owner.id.toString())
        assertThat(payload["discordName"].isNull).isTrue()
        assertThat(payload["nickname"].asText()).isEqualTo("Birthday")
        assertThat(payload["status"].asText()).isEqualTo("REQUESTED")
        assertThat(payload["previousStatus"].isNull).isTrue()
        assertThat(payload["items"].map { it["name"].asText() to it["quantity"].asInt() })
            .containsExactly(catalog.title to 2, customTitle to 1)
        assertThat(payload["itemsTotalEur"].asText()).isEqualTo("27.00")
        assertThat(payload["itemsTotalIsEstimate"].asBoolean()).isTrue()
    }

    @Test
    fun `status changes through admin, submit and shipping each send one event`() {
        val order = customOrder()
        assertThat(nextEvent()["type"].asText()).isEqualTo(ORDER_CREATED)

        orderService.updateStatus(order.id, OrderStatus.IN_PROGRESS)
        orderService.updateStatus(order.id, OrderStatus.IN_PROGRESS)
        val shipmentId = requireNotNull(orderService.submitForShipping(order.id, owner).shipmentId)
        shipmentService.update(shipmentId, UpdateShipmentRequest(status = ShipmentStatus.SHIPPED))

        val changes = List(3) { nextEvent() }
        assertThat(changes.map { it["type"].asText() }).containsOnly(ORDER_STATUS_CHANGED)
        assertThat(changes.map { it["order"]["previousStatus"].asText() to it["order"]["status"].asText() }).containsExactly(
            "REQUESTED" to "IN_PROGRESS",
            "IN_PROGRESS" to "PREPARING_SHIPPING",
            "PREPARING_SHIPPING" to "SENT",
        )
        assertThat(changes.map { it["eventId"].asText() }.distinct()).hasSize(3)
        assertThat(receiver.nothingWithin(300)).isTrue()
    }

    @Test
    fun `a cancel sends an event, a rejected change and a repeated submit send none`() {
        val order = customOrder()
        nextEvent()
        val repeat = CreateOrderRequest(items = listOf(CreateOrderItemRequest(title = "Repeat ${UUID.randomUUID()}", condition = Condition.NEW, currency = Currency.EUR)))
        orderService.create(owner, repeat)
        nextEvent()
        orderService.create(owner, repeat)

        orderService.cancelOwn(order.id, owner)
        assertThrows(ResponseStatusException::class.java) { orderService.updateStatus(order.id, OrderStatus.REQUESTED) }

        val cancelled = nextEvent()
        assertThat(cancelled["order"]["status"].asText()).isEqualTo("CANCELLED")
        assertThat(cancelled["order"]["previousStatus"].asText()).isEqualTo("REQUESTED")
        assertThat(receiver.nothingWithin(300)).isTrue()
    }
}
