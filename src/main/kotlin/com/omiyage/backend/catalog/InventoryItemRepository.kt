package com.omiyage.backend.catalog

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.UUID

interface InventoryItemRepository : JpaRepository<InventoryItem, UUID> {

    @Query(
        """
        SELECT DISTINCT i FROM InventoryItem i
        LEFT JOIN i.categories c
        WHERE (:title IS NULL OR LOWER(i.title) LIKE LOWER(CONCAT('%', :title, '%')))
        AND (:categoryId IS NULL OR c.id = :categoryId)
        AND (:status IS NULL OR i.status = :status)
        """,
    )
    fun search(
        @Param("title") title: String?,
        @Param("categoryId") categoryId: UUID?,
        @Param("status") status: InventoryStatus?,
        pageable: Pageable,
    ): Page<InventoryItem>
}
