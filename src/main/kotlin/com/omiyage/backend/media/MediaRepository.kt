package com.omiyage.backend.media

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.OffsetDateTime
import java.util.UUID

interface MediaRepository : JpaRepository<Media, String> {
    fun countByUploaderIdAndCreatedAtAfter(uploaderId: UUID, createdAt: OffsetDateTime): Long

    @Query(
        value = """
            SELECT EXISTS (SELECT 1 FROM inventory_item_images WHERE object_key = :key)
                OR EXISTS (SELECT 1 FROM order_items WHERE custom_image_key = :key)
        """,
        nativeQuery = true,
    )
    fun isReferenced(key: String): Boolean

    @Modifying
    @Query(
        value = """
            DELETE FROM media m
            WHERE m.created_at < :cutoff
              AND NOT EXISTS (SELECT 1 FROM inventory_item_images i WHERE i.object_key = m.object_key)
              AND NOT EXISTS (SELECT 1 FROM order_items o WHERE o.custom_image_key = m.object_key)
        """,
        nativeQuery = true,
    )
    fun purgeUnreferencedBefore(cutoff: OffsetDateTime): Int
}
