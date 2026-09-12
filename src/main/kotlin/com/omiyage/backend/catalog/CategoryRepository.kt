package com.omiyage.backend.catalog

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.OffsetDateTime
import java.util.UUID

interface CategoryRepository : JpaRepository<Category, UUID> {
    fun existsBySlug(slug: String): Boolean

    @Modifying
    @Query(value = "DELETE FROM categories WHERE deleted_at IS NOT NULL AND deleted_at < :cutoff", nativeQuery = true)
    fun purgeDeletedBefore(cutoff: OffsetDateTime): Int
}
