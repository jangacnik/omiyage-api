package com.omiyage.backend.catalog

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.OffsetDateTime
import java.util.UUID

interface InventoryItemImageRepository : JpaRepository<InventoryItemImage, UUID> {
    @Modifying
    @Query(value = "DELETE FROM inventory_item_images WHERE deleted_at IS NOT NULL AND deleted_at < :cutoff", nativeQuery = true)
    fun purgeDeletedBefore(cutoff: OffsetDateTime): Int
}
