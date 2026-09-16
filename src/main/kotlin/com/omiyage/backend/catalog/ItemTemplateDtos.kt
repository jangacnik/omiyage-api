package com.omiyage.backend.catalog

import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotBlank
import java.math.BigDecimal
import java.util.UUID

data class ItemTemplateDto(
    val id: UUID,
    val name: String,
    val title: String?,
    val description: String?,
    val priceAmount: BigDecimal?,
    val boughtPrice: BigDecimal?,
    val weight: BigDecimal?,
)

fun ItemTemplate.toDto() = ItemTemplateDto(
    id = requireNotNull(id),
    name = name,
    title = title,
    description = description,
    priceAmount = priceAmount,
    boughtPrice = boughtPrice,
    weight = weight,
)

data class ItemTemplateRequest(
    @field:NotBlank val name: String,
    val title: String? = null,
    val description: String? = null,
    @field:DecimalMin("0.0") val priceAmount: BigDecimal? = null,
    @field:DecimalMin("0.0") val boughtPrice: BigDecimal? = null,
    @field:DecimalMin("0.0") val weight: BigDecimal? = null,
)
