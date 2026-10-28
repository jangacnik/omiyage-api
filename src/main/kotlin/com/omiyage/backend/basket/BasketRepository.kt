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
    @Query(
        """
        DELETE FROM basket_lines WHERE user_id IN (
            SELECT user_id FROM basket_lines GROUP BY user_id HAVING MAX(updated_at) < :cutoff
        )
        """,
        nativeQuery = true,
    )
    fun purgeUntouchedSince(@Param("cutoff") cutoff: OffsetDateTime): Int
}
