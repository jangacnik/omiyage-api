package com.omiyage.backend.basket

import com.omiyage.backend.common.Currency
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

data class BasketLineDto(
    val id: UUID,
    val inventoryItemId: UUID?,
    val customTitle: String?,
    val customDescription: String?,
    val currency: Currency,
    val maxPrice: BigDecimal?,
    val quantity: Int,
    val createdAt: OffsetDateTime,
)

fun BasketLine.toDto() = BasketLineDto(
    id = requireNotNull(id),
    inventoryItemId = inventoryItemId,
    customTitle = customTitle,
    customDescription = customDescription,
    currency = currency,
    maxPrice = maxPrice,
    quantity = quantity,
    createdAt = createdAt,
)

data class AddBasketLineRequest(
    val inventoryItemId: UUID? = null,
    @field:Size(max = 200) val customTitle: String? = null,
    val customDescription: String? = null,
    @field:NotNull val currency: Currency?,
    @field:DecimalMin("0.00") val maxPrice: BigDecimal? = null,
    @field:Min(1) val quantity: Int = 1,
)

data class AddBasketToOrderRequest(@field:NotNull val orderId: UUID?)

data class UpdateBasketLineRequest(
    @field:Size(max = 200) val customTitle: String? = null,
    val customDescription: String? = null,
    @field:DecimalMin("0.00") val maxPrice: BigDecimal? = null,
    @field:Min(1) val quantity: Int = 1,
)
