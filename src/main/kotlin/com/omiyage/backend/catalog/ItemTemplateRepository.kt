package com.omiyage.backend.catalog

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ItemTemplateRepository : JpaRepository<ItemTemplate, UUID> {
    fun findAllByOrderByNameAsc(): List<ItemTemplate>
}
