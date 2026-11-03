package com.omiyage.backend.media

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.OffsetDateTime
import java.util.UUID

interface MediaRepository : JpaRepository<Media, String> {
    fun countByUploaderIdAndCreatedAtAfter(uploaderId: UUID, createdAt: OffsetDateTime): Long

    @Query(nativeQuery = true)
    fun isReferenced(key: String): Boolean

    @Modifying
    @Query(nativeQuery = true)
    fun purgeUnreferencedBefore(cutoff: OffsetDateTime): Int
}
