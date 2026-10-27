package com.omiyage.backend.order

import com.omiyage.backend.common.Condition
import com.omiyage.backend.common.Currency
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import jakarta.validation.Valid
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

data class OrderItemDto(
    val id: UUID,
    val inventoryItemId: UUID?,
    val title: String,
    val description: String?,
    val imageKey: String?,
    val condition: Condition?,
    val currency: Currency,
    val maxPrice: BigDecimal?,
    // What the admin actually paid, always JPY - admin-only, same gating as profit (see
    // toDto(includeProfit)). A normal user only ever sees maxPrice and soldPrice.
    val actualPrice: BigDecimal?,
    val actualPriceCurrency: Currency?,
    val status: OrderItemStatus,
    val paid: Boolean,
    val paidAt: OffsetDateTime?,
    val link: String?,
    // Admin-only - when the admin actually bought it, same gating as profit (see toDto(includeProfit)).
    val purchasedAt: OffsetDateTime? = null,
    val quantity: Int,
    val weight: BigDecimal?,
    val isPreorder: Boolean = false,
    val expectedReleaseDate: LocalDate? = null,
    // What the customer is charged - populated for the order's own owner too, not just admins.
    val soldPrice: BigDecimal? = null,
    val soldPriceCurrency: Currency? = null,
    // Admin-only - never populated on a response a normal user can read, see toDto(includeProfit).
    val profit: BigDecimal? = null,
)

/** profit = (soldPrice[EUR] - toEur(actualPrice[JPY])) * quantity, only when both are set.
 *  Bought-for is always JPY (that's where the admin actually buys) and sold-for is always EUR
 *  (that's what the customer is charged) - see OrderService.updateItem, which enforces this
 *  regardless of what a caller sends. `toEur` is the bare admin-set-rate conversion
 *  (ExchangeRateService.toEur), never the shipping markup rate. */
private fun OrderItem.profit(toEur: (BigDecimal) -> BigDecimal): BigDecimal? = soldPrice?.let { sold ->
    actualPrice?.let { bought ->
        // setScale explicitly: toEur() returns a scale-2 value, but sold/quantity may not be, and
        // BigDecimal subtraction/multiplication takes the larger of the two scales - without this,
        // profit's scale would silently depend on whether a conversion happened to run.
        ((sold - toEur(bought)) * BigDecimal(quantity)).setScale(2, java.math.RoundingMode.HALF_UP)
    }
}

fun OrderItem.toDto(includeProfit: Boolean = false, toEur: (BigDecimal) -> BigDecimal = { it }): OrderItemDto {
    val linked = inventoryItem
    return OrderItemDto(
        id = requireNotNull(id),
        inventoryItemId = linked?.id,
        title = linked?.title ?: requireNotNull(customTitle),
        description = linked?.description ?: customDescription,
        imageKey = linked?.images?.minByOrNull { it.sortOrder }?.objectKey ?: customImageKey,
        condition = linked?.condition ?: condition,
        currency = currency,
        maxPrice = maxPrice,
        actualPrice = if (includeProfit) actualPrice else null,
        actualPriceCurrency = if (includeProfit) actualPriceCurrency else null,
        status = status,
        paid = paid,
        paidAt = paidAt,
        link = link ?: linked?.link,
        purchasedAt = if (includeProfit) purchasedAt else null,
        quantity = quantity,
        weight = weight,
        isPreorder = isPreorder,
        expectedReleaseDate = expectedReleaseDate,
        soldPrice = soldPrice,
        soldPriceCurrency = soldPriceCurrency,
        profit = if (includeProfit) profit(toEur) else null,
    )
}

data class OrderDto(
    val id: UUID,
    val userId: UUID,
    val status: OrderStatus,
    val open: Boolean,
    val notes: String?,
    val nickname: String?,
    val shipmentId: UUID?,
    // The requester's account nickname - only populated on the admin orders list (see
    // OrderService.adminList), which is the only place a caller other than the order's own owner
    // needs to know whose order this is; every other endpoint leaves this null.
    val userNickname: String? = null,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
    val items: List<OrderItemDto>,
    // Public image URL of the first item, null when it has no image.
    val iconUrl: String? = null,
    // Only populated when this order is read as part of a shipment (see Shipment.toDto()) -
    // null on every other endpoint that returns an order.
    val orderWeightGrams: BigDecimal? = null,
    val shippingCostShareJpy: Int? = null,
    val shippingCostShareEur: BigDecimal? = null,
    // Admin-only - sum of each item's profit, null unless includeProfit was requested.
    val profit: BigDecimal? = null,
)

// Once an order is being prepared for shipping (or has shipped/been cancelled) its contents are
// settled - only REQUESTED, IN_PROGRESS and PURCHASED are still open to being edited or added to.
private val CLOSED_ORDER_STATUSES =
    setOf(OrderStatus.PREPARING_SHIPPING, OrderStatus.SENT, OrderStatus.DELIVERED, OrderStatus.CANCELLED)

val Order.isOpen: Boolean get() = status !in CLOSED_ORDER_STATUSES

fun Order.toDto(includeProfit: Boolean = false, toEur: (BigDecimal) -> BigDecimal = { it }): OrderDto {
    val itemDtos = items.map { it.toDto(includeProfit, toEur) }
    return OrderDto(
        id = requireNotNull(id),
        userId = userId,
        status = status,
        open = isOpen,
        notes = notes,
        nickname = nickname,
        shipmentId = shipment?.id,
        createdAt = createdAt,
        updatedAt = updatedAt,
        items = itemDtos,
        iconUrl = itemDtos.firstOrNull()?.imageKey?.let { "/api/images/$it" },
        profit = if (includeProfit) itemDtos.mapNotNull { it.profit }.fold(BigDecimal.ZERO, BigDecimal::add) else null,
    )
}

/** Sum of each item's weight * quantity, in grams. Items with no recorded weight count as 0. */
fun Order.totalWeightGrams(): BigDecimal =
    items.fold(BigDecimal.ZERO) { sum, item -> sum + (item.weight ?: BigDecimal.ZERO) * BigDecimal(item.quantity) }

const val MAX_ITEM_QUANTITY = 1000L

data class CreateOrderItemRequest(
    val inventoryItemId: UUID? = null,
    @field:Size(max = 200) val title: String? = null,
    val description: String? = null,
    @field:Size(max = 500) val imageKey: String? = null,
    val condition: Condition? = null,
    @field:NotNull val currency: Currency,
    @field:DecimalMin("0.0") val maxPrice: BigDecimal? = null,
    @field:Min(1) @field:Max(MAX_ITEM_QUANTITY) val quantity: Int = 1,
    @field:Size(max = 2048) val link: String? = null,
    val isPreorder: Boolean = false,
    val expectedReleaseDate: LocalDate? = null,
)

data class CreateOrderRequest(
    val notes: String? = null,
    @field:Size(max = 100) val nickname: String? = null,
    @field:NotEmpty @field:Valid val items: List<CreateOrderItemRequest>,
)

data class UpdateOrderStatusRequest(@field:NotNull val status: OrderStatus)

// actualPrice is always JPY and soldPrice is always EUR - see OrderService.updateItem - so there's
// no per-request currency choice for either.
data class UpdateOrderItemRequest(
    @field:NotNull val status: OrderItemStatus,
    @field:DecimalMin("0.0") val actualPrice: BigDecimal? = null,
    @field:DecimalMin("0.0") val soldPrice: BigDecimal? = null,
    @field:DecimalMin("0.0") val weight: BigDecimal? = null,
)

data class UpdateOwnOrderItemRequest(
    @field:Size(max = 200) val title: String? = null,
    val description: String? = null,
    @field:Size(max = 500) val imageKey: String? = null,
    @field:DecimalMin("0.0") val maxPrice: BigDecimal? = null,
    @field:Min(1) @field:Max(MAX_ITEM_QUANTITY) val quantity: Int? = null,
    @field:Size(max = 2048) val link: String? = null,
)

data class MarkItemPaidRequest(@field:NotNull val paid: Boolean)

data class MergeOrdersRequest(@field:NotEmpty val orderIds: List<UUID>)

data class UpdateOrderNicknameRequest(@field:Size(max = 100) val nickname: String? = null)

data class SplitOrderRequest(@field:NotEmpty val itemIds: List<UUID>)

data class SplitOrderResponseDto(val original: OrderDto, val created: OrderDto)

// targetOrderId null means "move onto a new order" - the admin equivalent of splitMine, but able
// to land items on an existing order of the same user instead of always creating a new one.
data class MoveOrderItemsRequest(@field:NotEmpty val itemIds: List<UUID>, val targetOrderId: UUID? = null)

data class MoveItemsResponseDto(val source: OrderDto, val target: OrderDto)

enum class AdminOrderSortBy { UPDATED_AT, CREATED_AT, ITEM_COUNT }
