package com.omiyage.backend.order

import com.omiyage.backend.common.Condition
import com.omiyage.backend.common.Currency
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import jakarta.validation.Valid
import java.math.BigDecimal
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
    val purchased: Boolean,
    val purchasedAt: OffsetDateTime?,
    val quantity: Int,
)

fun OrderItem.toDto(): OrderItemDto {
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
        purchased = purchased,
        purchasedAt = purchasedAt,
        quantity = quantity,
    )
}

data class OrderDto(
    val id: UUID,
    val userId: UUID,
    val status: OrderStatus,
    val notes: String?,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
    val items: List<OrderItemDto>,
)

fun Order.toDto() = OrderDto(
    id = requireNotNull(id),
    userId = userId,
    status = status,
    notes = notes,
    createdAt = createdAt,
    updatedAt = updatedAt,
    items = items.map { it.toDto() },
)

data class CreateOrderItemRequest(
    val inventoryItemId: UUID? = null,
    val title: String? = null,
    val description: String? = null,
    val imageKey: String? = null,
    val condition: Condition? = null,
    @field:NotNull val currency: Currency,
    @field:DecimalMin("0.0") val maxPrice: BigDecimal? = null,
    @field:Min(1) val quantity: Int = 1,
)

data class CreateOrderRequest(
    val notes: String? = null,
    @field:NotEmpty @field:Valid val items: List<CreateOrderItemRequest>,
)

data class UpdateOrderStatusRequest(@field:NotNull val status: OrderStatus)

data class UpdateOrderItemRequest(
    @field:NotNull val purchased: Boolean,
    @field:DecimalMin("0.0") val actualPrice: BigDecimal? = null,
)
