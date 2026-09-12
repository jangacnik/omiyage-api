package com.omiyage.backend.catalog

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import org.hibernate.annotations.SQLDelete
import org.hibernate.annotations.SQLRestriction
import java.time.OffsetDateTime
import java.util.UUID

@Entity
@Table(name = "inventory_item_images")
@SQLDelete(sql = "UPDATE inventory_item_images SET deleted_at = now() WHERE id = ?")
@SQLRestriction("deleted_at IS NULL")
class InventoryItemImage(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "inventory_item_id", nullable = false)
    var inventoryItem: InventoryItem,

    @Column(name = "object_key", nullable = false)
    var objectKey: String,

    @Column(name = "sort_order", nullable = false)
    var sortOrder: Int = 0,

    @Column(name = "deleted_at")
    var deletedAt: OffsetDateTime? = null,
)
