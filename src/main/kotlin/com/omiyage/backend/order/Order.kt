package com.omiyage.backend.order

import com.omiyage.backend.shipment.Shipment
import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToMany
import jakarta.persistence.OrderBy
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.annotations.SQLDelete
import org.hibernate.annotations.SQLRestriction
import java.time.OffsetDateTime
import java.util.UUID

enum class OrderStatus { REQUESTED, IN_PROGRESS, PURCHASED, PREPARING_SHIPPING, SENT, DELIVERED, CANCELLED }

@Entity
@Table(name = "orders")
@SQLDelete(sql = "UPDATE orders SET deleted_at = now() WHERE id = ?")
@SQLRestriction("deleted_at IS NULL")
// Nickname, status, and shipment assignment are each set from a separate endpoint/transaction -
// same multi-writer shape @DynamicUpdate was added to OrderItem for, so one save's stale
// snapshot of a field it didn't touch can't overwrite a concurrent save's change to that field.
@org.hibernate.annotations.DynamicUpdate
class Order(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    @Column(name = "user_id", nullable = false)
    val userId: UUID,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: OrderStatus = OrderStatus.REQUESTED,

    var notes: String? = null,

    @Column(length = 100)
    var nickname: String? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shipment_id")
    var shipment: Shipment? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime = OffsetDateTime.now(),

    @OneToMany(mappedBy = "order", cascade = [CascadeType.ALL], orphanRemoval = true)
    @OrderBy("createdAt ASC")
    val items: MutableList<OrderItem> = mutableListOf(),

    @Column(name = "deleted_at")
    var deletedAt: OffsetDateTime? = null,

    @Version
    var version: Long = 0,
)
