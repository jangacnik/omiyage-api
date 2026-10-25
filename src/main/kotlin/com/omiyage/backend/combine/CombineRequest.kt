package com.omiyage.backend.combine

import com.omiyage.backend.order.Order
import com.omiyage.backend.shipment.Shipment
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.Column
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.OffsetDateTime
import java.util.UUID

enum class CombineRequestStatus { PENDING, ACCEPTED, DECLINED, CANCELLED }

@Entity
@Table(name = "combine_requests")
class CombineRequest(
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,

    @ManyToOne
    @JoinColumn(name = "requester_order_id", nullable = false)
    val requesterOrder: Order,

    @ManyToOne
    @JoinColumn(name = "target_shipment_id", nullable = false)
    val targetShipment: Shipment,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: CombineRequestStatus = CombineRequestStatus.PENDING,

    @Column(name = "resulting_shipment_id")
    var resultingShipmentId: UUID? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),

    @Column(name = "responded_at")
    var respondedAt: OffsetDateTime? = null,

    @Version
    var version: Long = 0,
)
