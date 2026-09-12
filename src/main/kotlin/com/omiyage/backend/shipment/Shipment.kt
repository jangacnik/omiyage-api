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
import java.time.OffsetDateTime
import java.util.UUID

enum class ShipmentStatus { PREPARING, SHIPPED, DELIVERED }

@Entity
@Table(name = "shipments")
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

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime = OffsetDateTime.now(),

    @OneToMany(mappedBy = "shipment")
    @OrderBy("createdAt ASC")
    val orders: MutableList<Order> = mutableListOf(),
)
