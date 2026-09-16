package com.omiyage.backend.catalog

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.util.UUID

// Reusable presets an admin can apply when creating a new inventory item - every field but name
// is optional, since a template might exist just to preset a common weight, or just a common
// price, rather than every field an item has.
@Entity
@Table(name = "item_templates")
class ItemTemplate(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    @Column(nullable = false)
    var name: String,

    var title: String? = null,

    var description: String? = null,

    // Always EUR, same convention as InventoryItem.priceAmount.
    @Column(name = "price_amount")
    var priceAmount: BigDecimal? = null,

    // Always JPY, same convention as InventoryItem.boughtPrice.
    @Column(name = "bought_price")
    var boughtPrice: BigDecimal? = null,

    var weight: BigDecimal? = null,
)
