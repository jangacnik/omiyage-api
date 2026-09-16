package com.omiyage.backend.catalog

import com.omiyage.backend.common.Condition
import com.omiyage.backend.common.Currency
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

data class CategoryDto(val id: UUID, val name: String, val slug: String)

fun Category.toDto() = CategoryDto(requireNotNull(id), name, slug)

data class InventoryItemDto(
    val id: UUID,
    val title: String,
    val description: String?,
    val link: String?,
    val condition: Condition,
    val priceAmount: BigDecimal,
    val currency: Currency,
    val stockQuantity: Int,
    val status: InventoryStatus,
    val images: List<String>,
    val categories: List<CategoryDto>,
    val tags: List<String> = emptyList(),
    val isPreorder: Boolean = false,
    val expectedReleaseDate: LocalDate? = null,
    val weight: BigDecimal? = null,
    // Admin-only cost basis - never populated on a response a normal user can read, see toDto(includeCost).
    // Always JPY (that's where the admin actually buys); priceAmount above is always EUR.
    val boughtPrice: BigDecimal? = null,
    // priceAmount[EUR] - toEur(boughtPrice[JPY]) - admin-only, same convention as boughtPrice.
    val profitEur: BigDecimal? = null,
)

fun InventoryItem.toDto(includeCost: Boolean = false, toEur: (BigDecimal) -> BigDecimal = { it }) = InventoryItemDto(
    id = requireNotNull(id),
    title = title,
    description = description,
    link = link,
    condition = condition,
    priceAmount = priceAmount,
    currency = currency,
    stockQuantity = stockQuantity,
    status = status,
    images = images.sortedBy { it.sortOrder }.map { it.objectKey },
    categories = categories.map { it.toDto() }.sortedBy { it.name },
    tags = tags.sorted(),
    isPreorder = isPreorder,
    expectedReleaseDate = expectedReleaseDate,
    weight = weight,
    boughtPrice = if (includeCost) boughtPrice else null,
    profitEur = if (includeCost) boughtPrice?.let { priceAmount - toEur(it) } else null,
)

data class CreateCategoryRequest(
    @field:NotBlank val name: String,
    @field:NotBlank val slug: String,
)

data class InventoryItemRequest(
    @field:NotBlank val title: String,
    val description: String?,
    @field:Size(max = 2048) val link: String? = null,
    @field:NotNull val condition: Condition,
    @field:NotNull @field:DecimalMin("0.0") val priceAmount: BigDecimal,
    @field:NotNull val currency: Currency,
    @field:DecimalMin("0.0") val boughtPrice: BigDecimal? = null,
    val stockQuantity: Int = 0,
    @field:DecimalMin("0.0") val weight: BigDecimal? = null,
    @field:Size(max = 5, message = "an item can have at most 5 images") val imageKeys: List<String> = emptyList(),
    val categoryIds: List<UUID> = emptyList(),
    val tags: List<String> = emptyList(),
    val isPreorder: Boolean = false,
    val expectedReleaseDate: LocalDate? = null,
)

data class UpdateStatusRequest(@field:NotNull val status: InventoryStatus)
