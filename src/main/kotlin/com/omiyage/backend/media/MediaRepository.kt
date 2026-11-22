package com.omiyage.backend.media

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

interface MediaRepository : JpaRepository<Media, String> {
    fun countByUploaderIdAndCreatedAtAfter(uploaderId: UUID, createdAt: OffsetDateTime): Long

    @Query(nativeQuery = true)
    fun isReferenced(key: String): Boolean

    @Query(nativeQuery = true)
    fun findUnreferencedKeysBefore(cutoff: OffsetDateTime): List<String>

    @Query(nativeQuery = true)
    fun findKeysWithLegacyData(): List<String>

    @Transactional
    @Modifying
    @Query(nativeQuery = true)
    fun clearLegacyData(key: String): Int
}
