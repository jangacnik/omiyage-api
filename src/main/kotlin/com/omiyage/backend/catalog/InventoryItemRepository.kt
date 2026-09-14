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

    @Modifying
    @Query(value = "DELETE FROM inventory_items WHERE deleted_at IS NOT NULL AND deleted_at < :cutoff", nativeQuery = true)
    fun purgeDeletedBefore(cutoff: OffsetDateTime): Int

    @Query(
        """
        SELECT DISTINCT i FROM InventoryItem i
        LEFT JOIN i.categories c
        WHERE (:title = '' OR LOWER(i.title) LIKE LOWER(CONCAT('%', :title, '%')))
        AND (:categoryId IS NULL OR c.id = :categoryId)
        AND (:status IS NULL OR i.status = :status)
        AND (:inStockOnly = false OR i.stockQuantity > 0)
        """,
    )
    fun search(
        @Param("title") title: String,
        @Param("categoryId") categoryId: UUID?,
        @Param("status") status: InventoryStatus?,
        @Param("inStockOnly") inStockOnly: Boolean,
        pageable: Pageable,
    ): Page<InventoryItem>
}
