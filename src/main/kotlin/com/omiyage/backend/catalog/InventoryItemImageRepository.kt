package com.omiyage.backend.catalog

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.OffsetDateTime
import java.util.UUID

interface InventoryItemImageRepository : JpaRepository<InventoryItemImage, UUID> {
    @Modifying
    @Query(nativeQuery = true)
    fun purgeDeletedBefore(cutoff: OffsetDateTime): Int
}
