package com.omiyage.backend.catalog

import com.omiyage.backend.common.Condition
import com.omiyage.backend.common.Currency
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import java.math.BigDecimal
import java.util.UUID

data class CategoryDto(val id: UUID, val name: String, val slug: String)

fun Category.toDto() = CategoryDto(requireNotNull(id), name, slug)

data class InventoryItemDto(
    val id: UUID,
    val title: String,
    val description: String?,
    val condition: Condition,
    val priceAmount: BigDecimal,
    val currency: Currency,
    val stockQuantity: Int,
    val status: InventoryStatus,
    val images: List<String>,
    val categories: List<CategoryDto>,
)

fun InventoryItem.toDto() = InventoryItemDto(
    id = requireNotNull(id),
    title = title,
    description = description,
    condition = condition,
    priceAmount = priceAmount,
    currency = currency,
    stockQuantity = stockQuantity,
    status = status,
    images = images.sortedBy { it.sortOrder }.map { it.objectKey },
    categories = categories.map { it.toDto() }.sortedBy { it.name },
)

data class CreateCategoryRequest(
    @field:NotBlank val name: String,
    @field:NotBlank val slug: String,
)

data class InventoryItemRequest(
    @field:NotBlank val title: String,
    val description: String?,
    @field:NotNull val condition: Condition,
    @field:NotNull @field:DecimalMin("0.0") val priceAmount: BigDecimal,
    @field:NotNull val currency: Currency,
    val stockQuantity: Int = 0,
    val imageKeys: List<String> = emptyList(),
    val categoryIds: List<UUID> = emptyList(),
)

data class UpdateStatusRequest(@field:NotNull val status: InventoryStatus)
