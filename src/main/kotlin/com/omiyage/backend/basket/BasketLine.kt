package com.omiyage.backend.basket

import com.omiyage.backend.common.Currency
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

@Entity
@Table(name = "basket_lines")
class BasketLine(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    @Column(name = "user_id", nullable = false, updatable = false)
    val userId: UUID,

    @Column(name = "inventory_item_id", updatable = false)
    val inventoryItemId: UUID? = null,

    @Column(name = "custom_title")
    var customTitle: String? = null,

    @Column(name = "custom_description")
    var customDescription: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val currency: Currency,

    @Column(name = "max_price")
    var maxPrice: BigDecimal? = null,

    @Column(nullable = false)
    var quantity: Int = 1,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime = OffsetDateTime.now(),

    @Version
    var version: Long = 0,
)
