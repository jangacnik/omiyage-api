package com.omiyage.backend.shipment

import com.omiyage.backend.order.Order
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.OneToMany
import jakarta.persistence.OrderBy
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.annotations.SQLDelete
import org.hibernate.annotations.SQLRestriction
import java.time.OffsetDateTime
import java.util.UUID

enum class ShipmentStatus { PREPARING, SHIPPED, DELIVERED }

@Entity
@Table(name = "shipments")
@SQLDelete(sql = "UPDATE shipments SET deleted_at = now() WHERE id = ?")
@SQLRestriction("deleted_at IS NULL")
// Destination/method/tracking/title/status/ownerUserId are each set from separate
// endpoints/transactions - same multi-writer shape @DynamicUpdate was added to OrderItem for.
@org.hibernate.annotations.DynamicUpdate
class Shipment(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: ShipmentStatus = ShipmentStatus.PREPARING,

    @Enumerated(EnumType.STRING)
    var destination: ShipmentDestination? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "shipping_method")
    var shippingMethod: ShippingMethod? = null,

    @Column(name = "tracking_number")
    var trackingNumber: String? = null,

    var notes: String? = null,

    @Column(length = 100)
    var title: String? = null,

    @Column(name = "shipped_at")
    var shippedAt: OffsetDateTime? = null,

    @Column(name = "arrived_at")
    var arrivedAt: OffsetDateTime? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime = OffsetDateTime.now(),

    @OneToMany(mappedBy = "shipment")
    @OrderBy("createdAt ASC")
    val orders: MutableList<Order> = mutableListOf(),

    // The user whose order this shipment was created for/absorbed into when orders were combined -
    // only they may submit it for shipping, even if other users' orders got combined in alongside.
    @Column(name = "owner_user_id")
    var ownerUserId: UUID? = null,

    @Column(name = "deleted_at")
    var deletedAt: OffsetDateTime? = null,

    @Version
    var version: Long = 0,
)
