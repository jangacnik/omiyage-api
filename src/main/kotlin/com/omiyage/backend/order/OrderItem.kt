package com.omiyage.backend.order

import com.omiyage.backend.catalog.InventoryItem
import com.omiyage.backend.common.Condition
import com.omiyage.backend.common.Currency
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import org.hibernate.annotations.SQLDelete
import org.hibernate.annotations.SQLRestriction
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

enum class OrderItemStatus { REQUESTED, CONFIRMING, BUYING, BOUGHT, SHIPPED }

@Entity
@Table(name = "order_items")
@SQLDelete(sql = "UPDATE order_items SET deleted_at = now() WHERE id = ?")
@SQLRestriction("deleted_at IS NULL")
class OrderItem(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    var order: Order,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "inventory_item_id")
    var inventoryItem: InventoryItem? = null,

    @Column(name = "custom_title")
    var customTitle: String? = null,

    @Column(name = "custom_description")
    var customDescription: String? = null,

    @Column(name = "custom_image_key")
    var customImageKey: String? = null,

    @Enumerated(EnumType.STRING)
    var condition: Condition? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var currency: Currency,

    @Column(name = "max_price")
    var maxPrice: BigDecimal? = null,

    @Column(name = "actual_price")
    var actualPrice: BigDecimal? = null,

    @Column(name = "sold_price")
    var soldPrice: BigDecimal? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: OrderItemStatus = OrderItemStatus.REQUESTED,

    @Column(nullable = false)
    var paid: Boolean = false,

    @Column(name = "paid_at")
    var paidAt: OffsetDateTime? = null,

    var link: String? = null,

    @Column(name = "purchased_at")
    var purchasedAt: OffsetDateTime? = null,

    @Column(nullable = false)
    var quantity: Int = 1,

    var weight: BigDecimal? = null,

    @Column(name = "is_preorder", nullable = false)
    var isPreorder: Boolean = false,

    @Column(name = "expected_release_date")
    var expectedReleaseDate: java.time.LocalDate? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "deleted_at")
    var deletedAt: OffsetDateTime? = null,
) {
    init {
        require(inventoryItem != null || customTitle != null) {
            "order item must reference an inventory item or provide a custom title"
        }
        require(!isPreorder || expectedReleaseDate != null) {
            "a preorder item must have an expected release date"
        }
    }
}
