package com.omiyage.backend.catalog

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.OffsetDateTime
import java.util.UUID

interface InventoryItemRepository : JpaRepository<InventoryItem, UUID> {
    fun existsByTitleIgnoreCaseAndConditionAndLink(title: String, condition: com.omiyage.backend.common.Condition, link: String?): Boolean

    @Modifying
    @Query(nativeQuery = true)
    fun purgeDeletedBefore(cutoff: OffsetDateTime): Int

    fun search(
        @Param("title") title: String,
        @Param("categoryId") categoryId: UUID?,
        @Param("status") status: InventoryStatus?,
        @Param("inStockOnly") inStockOnly: Boolean,
        pageable: Pageable,
    ): Page<InventoryItem>
}
