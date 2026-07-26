package com.omiyage.backend.catalog

import com.omiyage.backend.common.Condition
import com.omiyage.backend.common.Currency
import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.JoinTable
import jakarta.persistence.ManyToMany
import jakarta.persistence.OneToMany
import jakarta.persistence.OrderBy
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

enum class InventoryStatus { ACTIVE, ARCHIVED }

@Entity
@Table(name = "inventory_items")
class InventoryItem(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    @Column(nullable = false)
    var title: String,

    var description: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var condition: Condition,

    @Column(name = "price_amount", nullable = false)
    var priceAmount: BigDecimal,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var currency: Currency,

    @Column(name = "stock_quantity", nullable = false)
    var stockQuantity: Int = 0,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: InventoryStatus = InventoryStatus.ACTIVE,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime = OffsetDateTime.now(),

    @OneToMany(mappedBy = "inventoryItem", cascade = [CascadeType.ALL], orphanRemoval = true)
    @OrderBy("sortOrder ASC")
    val images: MutableList<InventoryItemImage> = mutableListOf(),

    @ManyToMany
    @JoinTable(
        name = "inventory_item_categories",
        joinColumns = [JoinColumn(name = "inventory_item_id")],
        inverseJoinColumns = [JoinColumn(name = "category_id")],
    )
    val categories: MutableSet<Category> = mutableSetOf(),
)
