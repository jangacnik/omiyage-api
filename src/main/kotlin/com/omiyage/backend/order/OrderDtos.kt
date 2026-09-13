package com.omiyage.backend.order

import com.omiyage.backend.common.Condition
import com.omiyage.backend.common.Currency
import jakarta.validation.constraints.DecimalMin
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
    val actualPrice: BigDecimal?,
    val actualPriceCurrency: Currency?,
    val status: OrderItemStatus,
    val paid: Boolean,
    val paidAt: OffsetDateTime?,
    val link: String?,
    val purchasedAt: OffsetDateTime?,
    val quantity: Int,
    val weight: BigDecimal?,
    val isPreorder: Boolean = false,
    val expectedReleaseDate: LocalDate? = null,
    // Admin-only - never populated on a response a normal user can read, see toDto(includeProfit).
    val soldPrice: BigDecimal? = null,
    val soldPriceCurrency: Currency? = null,
    val profit: BigDecimal? = null,
)

/** profit = soldPrice - actualPrice (the bought price), only when both are set and in the same
 *  currency - converting between JPY and EUR here would need a rate, and this is cost-basis
 *  bookkeeping, not a customer-facing amount, so it's not worth guessing at. Null currency on
 *  either side (rows from before per-price currencies existed) is treated as this item's own
 *  `currency`. */
private fun OrderItem.profit(): BigDecimal? = soldPrice?.let { sold ->
    actualPrice?.let { bought ->
        val soldCurrency = soldPriceCurrency ?: currency
        val boughtCurrency = actualPriceCurrency ?: currency
        if (soldCurrency == boughtCurrency) sold - bought else null
    }
}

fun OrderItem.toDto(includeProfit: Boolean = false): OrderItemDto {
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
        actualPrice = actualPrice,
        actualPriceCurrency = actualPriceCurrency,
        status = status,
        paid = paid,
        paidAt = paidAt,
        link = link ?: linked?.link,
        purchasedAt = purchasedAt,
        quantity = quantity,
        weight = weight,
        isPreorder = isPreorder,
        expectedReleaseDate = expectedReleaseDate,
        soldPrice = if (includeProfit) soldPrice else null,
        soldPriceCurrency = if (includeProfit) soldPriceCurrency else null,
        profit = if (includeProfit) profit() else null,
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

fun Order.toDto(includeProfit: Boolean = false): OrderDto {
    val itemDtos = items.map { it.toDto(includeProfit) }
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
        profit = if (includeProfit) itemDtos.mapNotNull { it.profit }.fold(BigDecimal.ZERO, BigDecimal::add) else null,
    )
}

/** Sum of each item's weight * quantity, in grams. Items with no recorded weight count as 0. */
fun Order.totalWeightGrams(): BigDecimal =
    items.fold(BigDecimal.ZERO) { sum, item -> sum + (item.weight ?: BigDecimal.ZERO) * BigDecimal(item.quantity) }

data class CreateOrderItemRequest(
    val inventoryItemId: UUID? = null,
    val title: String? = null,
    val description: String? = null,
    val imageKey: String? = null,
    val condition: Condition? = null,
    @field:NotNull val currency: Currency,
    @field:DecimalMin("0.0") val maxPrice: BigDecimal? = null,
    @field:Min(1) val quantity: Int = 1,
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

data class UpdateOrderItemRequest(
    @field:NotNull val status: OrderItemStatus,
    @field:DecimalMin("0.0") val actualPrice: BigDecimal? = null,
    val actualPriceCurrency: Currency? = null,
    @field:DecimalMin("0.0") val soldPrice: BigDecimal? = null,
    val soldPriceCurrency: Currency? = null,
    val weight: BigDecimal? = null,
)

data class UpdateOwnOrderItemRequest(
    val title: String? = null,
    val description: String? = null,
    val imageKey: String? = null,
    @field:DecimalMin("0.0") val maxPrice: BigDecimal? = null,
    @field:Min(1) val quantity: Int? = null,
    @field:Size(max = 2048) val link: String? = null,
)

data class MarkItemPaidRequest(@field:NotNull val paid: Boolean)

data class MergeOrdersRequest(@field:NotEmpty val orderIds: List<UUID>)

data class UpdateOrderNicknameRequest(@field:Size(max = 100) val nickname: String? = null)

data class SplitOrderRequest(@field:NotEmpty val itemIds: List<UUID>)

data class SplitOrderResponseDto(val original: OrderDto, val created: OrderDto)

enum class AdminOrderSortBy { UPDATED_AT, CREATED_AT, ITEM_COUNT }
