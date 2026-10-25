package com.omiyage.backend.media

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.OffsetDateTime

@Entity
@Table(name = "media")
class Media(
    @Id
    @Column(name = "object_key")
    val objectKey: String,

    @Column(name = "content_type", nullable = false)
    val contentType: String,

    @Column(name = "data", nullable = false)
    val data: ByteArray,

    @Column(name = "created_at", nullable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),
)
