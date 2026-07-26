package com.omiyage.backend.catalog

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface CategoryRepository : JpaRepository<Category, UUID> {
    fun existsBySlug(slug: String): Boolean
}
