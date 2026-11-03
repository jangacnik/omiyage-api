package com.omiyage.backend.basket

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.OffsetDateTime
import java.util.UUID

interface BasketRepository : JpaRepository<BasketLine, UUID> {
    fun findByUserIdOrderByCreatedAt(userId: UUID): List<BasketLine>
    fun findByIdAndUserId(id: UUID, userId: UUID): BasketLine?
    fun deleteByUserId(userId: UUID): Long

    @Modifying(clearAutomatically = true)
    @Query(nativeQuery = true)
    fun purgeUntouchedSince(@Param("cutoff") cutoff: OffsetDateTime): Int
}
