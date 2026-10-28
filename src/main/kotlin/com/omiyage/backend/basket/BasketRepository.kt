package com.omiyage.backend.basket

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface BasketRepository : JpaRepository<BasketLine, UUID> {
    fun findByUserIdOrderByCreatedAt(userId: UUID): List<BasketLine>
    fun findByIdAndUserId(id: UUID, userId: UUID): BasketLine?
    fun deleteByUserId(userId: UUID): Long
}
