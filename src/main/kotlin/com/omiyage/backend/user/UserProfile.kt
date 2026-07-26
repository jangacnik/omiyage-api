package com.omiyage.backend.user

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.OffsetDateTime
import java.util.UUID

@Entity
@Table(name = "user_profiles")
class UserProfile(
    @Id
    val id: UUID,

    @Column(nullable = false)
    var nickname: String,

    var email: String? = null,

    var phone: String? = null,

    @Column(name = "shipping_address")
    var shippingAddress: String? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime = OffsetDateTime.now(),
)
