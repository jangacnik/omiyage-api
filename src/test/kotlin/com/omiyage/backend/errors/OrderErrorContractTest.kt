package com.omiyage.backend.errors

import com.omiyage.backend.common.Condition
import com.omiyage.backend.common.Currency
import com.omiyage.backend.order.CreateOrderItemRequest
import com.omiyage.backend.order.CreateOrderRequest
import com.omiyage.backend.order.MarkItemPaidRequest
import com.omiyage.backend.order.MergeOrdersRequest
import com.omiyage.backend.order.MoveOrderItemsRequest
import com.omiyage.backend.order.OrderItemStatus
import com.omiyage.backend.order.OrderStatus
import com.omiyage.backend.order.SplitOrderRequest
import com.omiyage.backend.order.UpdateOrderItemRequest
import com.omiyage.backend.order.UpdateOrderNicknameRequest
import com.omiyage.backend.order.UpdateOrderStatusRequest
import com.omiyage.backend.order.UpdateOwnOrderItemRequest
import com.omiyage.backend.shipment.ShipmentStatus
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import java.util.UUID

private const val CLOSED = "order is closed and can no longer be edited"

class OrderErrorContractTest : ErrorContractTest() {

    private val user = newCaller()
    private val other = newCaller()
    private val admin = newCaller("ADMIN")

    private fun customItem(link: String? = null, title: String = uniqueTitle()) =
        CreateOrderItemRequest(title = title, condition = Condition.NEW, currency = Currency.EUR, link = link)

    private fun markBought(orderId: UUID, itemId: UUID) {
        val result = send(admin, HttpMethod.PATCH, "/api/admin/orders/$orderId/items/$itemId", UpdateOrderItemRequest(status = OrderItemStatus.BOUGHT))
        org.junit.jupiter.api.Assertions.assertEquals(200, result.response.status)
    }

    @Test
    fun `unknown order is 404`() {
        assertNotFound(send(user, HttpMethod.GET, "/api/orders/${UUID.randomUUID()}"), "order not found")
    }

    @Test
    fun `someone else's order is 404`() {
        val order = createOrder(other)
        assertNotFound(send(user, HttpMethod.GET, "/api/orders/${uuidOf(order)}"), "order not found")
    }

    @Test
    fun `creating an order for an unknown inventory item is 404`() {
        val body = CreateOrderRequest(items = listOf(CreateOrderItemRequest(inventoryItemId = UUID.randomUUID(), currency = Currency.EUR)))
        assertNotFound(send(user, HttpMethod.POST, "/api/orders", body), "inventory item not found")
    }

    @Test
    fun `an order item with neither inventory item nor title is 400`() {
        val body = CreateOrderRequest(items = listOf(customItem(title = " ")))
        assertBadRequest(send(user, HttpMethod.POST, "/api/orders", body), "each order item needs an inventoryItemId or a title")
    }

    @Test
    fun `a custom order item without a condition is 400`() {
        val body = CreateOrderRequest(items = listOf(CreateOrderItemRequest(title = uniqueTitle(), currency = Currency.EUR)))
        assertBadRequest(send(user, HttpMethod.POST, "/api/orders", body), "condition is required for a custom order item")
    }

    @Test
    fun `a preorder order item without a release date is 400`() {
        val body = CreateOrderRequest(items = listOf(CreateOrderItemRequest(title = uniqueTitle(), condition = Condition.NEW, currency = Currency.EUR, isPreorder = true)))
        assertBadRequest(send(user, HttpMethod.POST, "/api/orders", body), "a preorder item must have an expected release date")
    }

    @Test
    fun `an unknown image key on an order item is 400`() {
        val body = CreateOrderRequest(items = listOf(CreateOrderItemRequest(title = uniqueTitle(), condition = Condition.NEW, currency = Currency.EUR, imageKey = "uploads/missing.png")))
        assertBadRequest(send(user, HttpMethod.POST, "/api/orders", body), "unknown image key: uploads/missing.png")
    }

    @Test
    fun `an order item id that is not a uuid names the field`() {
        val result = mockMvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/orders")
                .header("Authorization", "Bearer ${user.token}")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("""{"items":[{"inventoryItemId":"not-a-uuid","currency":"EUR"}]}"""),
        ).andReturn()

        org.junit.jupiter.api.Assertions.assertEquals(400, result.response.status)
        val body = json(result)
        org.junit.jupiter.api.Assertions.assertEquals("items[0].inventoryItemId is not a valid id", body.get("error").asText())
        org.junit.jupiter.api.Assertions.assertEquals("bad_request", body.get("code").asText())
        org.junit.jupiter.api.Assertions.assertEquals("items[0].inventoryItemId", body.get("field").asText())
    }

    @Test
    fun `a delivered order cannot change status`() {
        val orderId = uuidOf(createOrder(user))
        setOrderStatus(admin, orderId, OrderStatus.DELIVERED)
        val result = send(admin, HttpMethod.PATCH, "/api/admin/orders/$orderId", UpdateOrderStatusRequest(OrderStatus.CANCELLED))
        assertConflict(result, "cannot change order status from DELIVERED to CANCELLED")
    }

    @Test
    fun `admin status change on an unknown order is 404`() {
        val result = send(admin, HttpMethod.PATCH, "/api/admin/orders/${UUID.randomUUID()}", UpdateOrderStatusRequest(OrderStatus.IN_PROGRESS))
        assertNotFound(result, "order not found")
    }

    @Test
    fun `admin item update on an unknown item is 404`() {
        val orderId = uuidOf(createOrder(user))
        val result = send(admin, HttpMethod.PATCH, "/api/admin/orders/$orderId/items/${UUID.randomUUID()}", UpdateOrderItemRequest(status = OrderItemStatus.BOUGHT))
        assertNotFound(result, "order item not found")
    }

    @Test
    fun `admin item update through another order is 404`() {
        val orderId = uuidOf(createOrder(user))
        val foreignItemId = orderItemIds(createOrder(other)).first()
        val result = send(admin, HttpMethod.PATCH, "/api/admin/orders/$orderId/items/$foreignItemId", UpdateOrderItemRequest(status = OrderItemStatus.BOUGHT))
        assertNotFound(result, "order item not found")
    }

    @Test
    fun `adding an item to someone else's order is 404`() {
        val orderId = uuidOf(createOrder(other))
        assertNotFound(send(user, HttpMethod.POST, "/api/orders/$orderId/items", customItem()), "order not found")
    }

    @Test
    fun `adding an item to a closed order is 409`() {
        val orderId = uuidOf(createOrder(user))
        setOrderStatus(admin, orderId, OrderStatus.PREPARING_SHIPPING)
        assertConflict(send(user, HttpMethod.POST, "/api/orders/$orderId/items", customItem()), CLOSED)
    }

    @Test
    fun `updating an unknown item of an own order is 404`() {
        val orderId = uuidOf(createOrder(user))
        val result = send(user, HttpMethod.PATCH, "/api/orders/$orderId/items/${UUID.randomUUID()}", UpdateOwnOrderItemRequest(title = "x"))
        assertNotFound(result, "order item not found")
    }

    @Test
    fun `editing a bought item is 409`() {
        val order = createOrder(user)
        val orderId = uuidOf(order)
        val itemId = orderItemIds(order).first()
        markBought(orderId, itemId)
        val result = send(user, HttpMethod.PATCH, "/api/orders/$orderId/items/$itemId", UpdateOwnOrderItemRequest(title = "x"))
        assertConflict(result, "item has already been bought or shipped and can no longer be edited")
    }

    @Test
    fun `removing a bought item is 409`() {
        val order = createOrder(user)
        val orderId = uuidOf(order)
        val itemId = orderItemIds(order).first()
        markBought(orderId, itemId)
        val result = send(user, HttpMethod.DELETE, "/api/orders/$orderId/items/$itemId")
        assertConflict(result, "item has already been bought or shipped and can no longer be removed")
    }

    @Test
    fun `adding a catalog-linked item to inventory is 400`() {
        val inventoryItemId = createInventoryItem(admin)
        val order = createOrderFromInventory(user, inventoryItemId)
        val itemId = orderItemIds(order).first()
        val result = send(admin, HttpMethod.POST, "/api/admin/orders/${uuidOf(order)}/items/$itemId/add-to-inventory")
        assertBadRequest(result, "only a custom order item can be added to inventory")
    }

    @Test
    fun `adding a custom item that already exists in inventory is 409`() {
        val title = uniqueTitle()
        val link = "https://shop.test/${UUID.randomUUID()}"
        createInventoryItem(admin, link = link, title = title)
        val created = send(user, HttpMethod.POST, "/api/orders", CreateOrderRequest(items = listOf(customItem(link = link, title = title))))
        val order = json(created)
        val itemId = orderItemIds(order).first()
        val result = send(admin, HttpMethod.POST, "/api/admin/orders/${uuidOf(order)}/items/$itemId/add-to-inventory")
        assertConflict(result, "an inventory item with this title, condition and link already exists")
    }

    @Test
    fun `admin marking an unknown item paid is 404`() {
        val orderId = uuidOf(createOrder(user))
        val result = send(admin, HttpMethod.PATCH, "/api/admin/orders/$orderId/items/${UUID.randomUUID()}/paid", MarkItemPaidRequest(true))
        assertNotFound(result, "order item not found")
    }

    @Test
    fun `moving an unknown item is 404`() {
        val orderId = uuidOf(createOrder(user, itemCount = 2))
        val missing = UUID.randomUUID()
        val result = send(admin, HttpMethod.POST, "/api/admin/orders/$orderId/items/move", MoveOrderItemsRequest(itemIds = listOf(missing)))
        assertNotFound(result, "order item not found: $missing")
    }

    @Test
    fun `moving every item is 400`() {
        val order = createOrder(user, itemCount = 2)
        val result = send(admin, HttpMethod.POST, "/api/admin/orders/${uuidOf(order)}/items/move", MoveOrderItemsRequest(itemIds = orderItemIds(order)))
        assertBadRequest(result, "must move at least one item and leave at least one behind")
    }

    @Test
    fun `moving items onto the same order is 400`() {
        val order = createOrder(user, itemCount = 2)
        val orderId = uuidOf(order)
        val body = MoveOrderItemsRequest(itemIds = listOf(orderItemIds(order).first()), targetOrderId = orderId)
        assertBadRequest(send(admin, HttpMethod.POST, "/api/admin/orders/$orderId/items/move", body), "target order must be different from the source order")
    }

    @Test
    fun `moving items to an unknown order is 404`() {
        val order = createOrder(user, itemCount = 2)
        val body = MoveOrderItemsRequest(itemIds = listOf(orderItemIds(order).first()), targetOrderId = UUID.randomUUID())
        assertNotFound(send(admin, HttpMethod.POST, "/api/admin/orders/${uuidOf(order)}/items/move", body), "order not found")
    }

    @Test
    fun `moving items to another user's order is 409`() {
        val order = createOrder(user, itemCount = 2)
        val target = createOrder(other)
        val body = MoveOrderItemsRequest(itemIds = listOf(orderItemIds(order).first()), targetOrderId = uuidOf(target))
        assertConflict(send(admin, HttpMethod.POST, "/api/admin/orders/${uuidOf(order)}/items/move", body), "cannot move items to an order belonging to a different user")
    }

    @Test
    fun `cancelling an order with a bought item is 409`() {
        val order = createOrder(user)
        val orderId = uuidOf(order)
        markBought(orderId, orderItemIds(order).first())
        assertConflict(send(user, HttpMethod.POST, "/api/orders/$orderId/cancel"), "order has items already bought or shipped and cannot be cancelled")
    }

    @Test
    fun `cancelling someone else's order is 404`() {
        val orderId = uuidOf(createOrder(other))
        assertNotFound(send(user, HttpMethod.POST, "/api/orders/$orderId/cancel"), "order not found")
    }

    @Test
    fun `cancelling a closed order is 409`() {
        val orderId = uuidOf(createOrder(user))
        setOrderStatus(admin, orderId, OrderStatus.PREPARING_SHIPPING)
        assertConflict(send(user, HttpMethod.POST, "/api/orders/$orderId/cancel"), CLOSED)
    }

    @Test
    fun `splitting off an unknown item is 404`() {
        val orderId = uuidOf(createOrder(user, itemCount = 2))
        val missing = UUID.randomUUID()
        assertNotFound(send(user, HttpMethod.POST, "/api/orders/$orderId/split", SplitOrderRequest(listOf(missing))), "order item not found: $missing")
    }

    @Test
    fun `splitting off every item is 400`() {
        val order = createOrder(user, itemCount = 2)
        val result = send(user, HttpMethod.POST, "/api/orders/${uuidOf(order)}/split", SplitOrderRequest(orderItemIds(order)))
        assertBadRequest(result, "split must move at least one item and leave at least one behind")
    }

    @Test
    fun `splitting someone else's order is 404`() {
        val order = createOrder(other, itemCount = 2)
        val result = send(user, HttpMethod.POST, "/api/orders/${uuidOf(order)}/split", SplitOrderRequest(listOf(orderItemIds(order).first())))
        assertNotFound(result, "order not found")
    }

    @Test
    fun `splitting a closed order is 409`() {
        val order = createOrder(user, itemCount = 2)
        val orderId = uuidOf(order)
        setOrderStatus(admin, orderId, OrderStatus.PREPARING_SHIPPING)
        val result = send(user, HttpMethod.POST, "/api/orders/$orderId/split", SplitOrderRequest(listOf(orderItemIds(order).first())))
        assertConflict(result, CLOSED)
    }

    @Test
    fun `submitting an order with a preorder item is 409`() {
        val inventoryItemId = createInventoryItem(admin, preorder = true)
        val orderId = uuidOf(createOrderFromInventory(user, inventoryItemId))
        val result = send(user, HttpMethod.POST, "/api/orders/$orderId/submit-for-shipping")
        assertConflict(result, "order has a preorder item that has not released yet and cannot be submitted for shipping")
    }

    @Test
    fun `submitting someone else's order is 404`() {
        val orderId = uuidOf(createOrder(other))
        assertNotFound(send(user, HttpMethod.POST, "/api/orders/$orderId/submit-for-shipping"), "order not found")
    }

    @Test
    fun `submitting a closed order is 409`() {
        val orderId = uuidOf(createOrder(user))
        setOrderStatus(admin, orderId, OrderStatus.PREPARING_SHIPPING)
        assertConflict(send(user, HttpMethod.POST, "/api/orders/$orderId/submit-for-shipping"), CLOSED)
    }

    @Test
    fun `merging fewer than two distinct orders is 400`() {
        val orderId = uuidOf(createOrder(user))
        assertBadRequest(send(user, HttpMethod.POST, "/api/orders/merge", MergeOrdersRequest(listOf(orderId, orderId))), "select at least two different orders to merge")
    }

    @Test
    fun `merging an unknown order is 404`() {
        val orderId = uuidOf(createOrder(user))
        assertNotFound(send(user, HttpMethod.POST, "/api/orders/merge", MergeOrdersRequest(listOf(orderId, UUID.randomUUID()))), "order not found")
    }

    @Test
    fun `merging someone else's order is 404`() {
        val mine = uuidOf(createOrder(user))
        val theirs = uuidOf(createOrder(other))
        assertNotFound(send(user, HttpMethod.POST, "/api/orders/merge", MergeOrdersRequest(listOf(mine, theirs))), "order not found")
    }

    @Test
    fun `merging a closed order is 409`() {
        val first = uuidOf(createOrder(user))
        val second = uuidOf(createOrder(user))
        setOrderStatus(admin, second, OrderStatus.CANCELLED)
        assertConflict(send(user, HttpMethod.POST, "/api/orders/merge", MergeOrdersRequest(listOf(first, second))), CLOSED)
    }

    @Test
    fun `merging orders of a shipment that already shipped is 409`() {
        val first = uuidOf(createOrder(user))
        val second = uuidOf(createOrder(user))
        forceShipmentStatus(createShipment(admin, first, second), ShipmentStatus.SHIPPED)
        assertConflict(send(user, HttpMethod.POST, "/api/orders/merge", MergeOrdersRequest(listOf(first, second))), "an order that has already shipped cannot be merged")
    }

    @Test
    fun `merging two orders already submitted for shipping is 409`() {
        val first = uuidOf(createOrder(user))
        val second = uuidOf(createOrder(user))
        send(user, HttpMethod.POST, "/api/orders/$first/submit-for-shipping")
        send(user, HttpMethod.POST, "/api/orders/$second/submit-for-shipping")
        assertConflict(
            send(user, HttpMethod.POST, "/api/orders/merge", MergeOrdersRequest(listOf(first, second))),
            "cannot merge two orders that have both already been submitted for shipping",
        )
    }

    @Test
    fun `admin merging orders of different users is 409`() {
        val first = uuidOf(createOrder(user))
        val second = uuidOf(createOrder(other))
        assertConflict(send(admin, HttpMethod.POST, "/api/admin/orders/merge", MergeOrdersRequest(listOf(first, second))), "cannot merge orders belonging to different users")
    }

    @Test
    fun `renaming someone else's order is 404`() {
        val orderId = uuidOf(createOrder(other))
        assertNotFound(send(user, HttpMethod.PATCH, "/api/orders/$orderId/nickname", UpdateOrderNicknameRequest("mine")), "order not found")
    }

    @Test
    fun `admin deleting an unknown order is 404`() {
        assertNotFound(send(admin, HttpMethod.DELETE, "/api/admin/orders/${UUID.randomUUID()}"), "order not found")
    }
}
