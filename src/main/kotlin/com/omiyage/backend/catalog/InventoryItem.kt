package com.omiyage.backend.catalog

import com.omiyage.backend.common.Condition
import com.omiyage.backend.common.Currency
import jakarta.persistence.CascadeType
import jakarta.persistence.CollectionTable
import jakarta.persistence.Column
import jakarta.persistence.ElementCollection
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
import jakarta.persistence.Version
import jakarta.persistence.Table
import org.hibernate.annotations.SQLDelete
import org.hibernate.annotations.SQLRestriction
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

enum class InventoryStatus { ACTIVE, ARCHIVED }

@Entity
@Table(name = "inventory_items")
@SQLDelete(sql = "UPDATE inventory_items SET deleted_at = now() WHERE id = ?")
@SQLRestriction("deleted_at IS NULL")
class InventoryItem(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    @Column(nullable = false)
    var title: String,

    var description: String? = null,

    var link: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var condition: Condition,

    @Column(name = "price_amount", nullable = false)
    var priceAmount: BigDecimal,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var currency: Currency,

    @Column(name = "bought_price")
    var boughtPrice: BigDecimal? = null,

    @Column(name = "stock_quantity", nullable = false)
    var stockQuantity: Int = 0,

    // Stamped onto an order item at add-time (see OrderService.buildOrderItem) so a linked item's
    // shipping weight is known immediately instead of waiting for an admin to fill it in per order.
    var weight: BigDecimal? = null,

    @Column(name = "is_preorder", nullable = false)
    var isPreorder: Boolean = false,

    @Column(name = "expected_release_date")
    var expectedReleaseDate: java.time.LocalDate? = null,

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

    // Freeform, admin-entered - distinct from the curated Category taxonomy above.
    @ElementCollection
    @CollectionTable(name = "inventory_item_tags", joinColumns = [JoinColumn(name = "inventory_item_id")])
    @Column(name = "tag")
    val tags: MutableSet<String> = mutableSetOf(),

    @Column(name = "deleted_at")
    var deletedAt: OffsetDateTime? = null,

    @Version
    var version: Long = 0,
) {
    init {
        require(!isPreorder || expectedReleaseDate != null) {
            "a preorder item must have an expected release date"
        }
    }
}
