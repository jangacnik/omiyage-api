package com.omiyage.backend.shipment

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.OffsetDateTime
import java.util.UUID

interface ShipmentRepository : JpaRepository<Shipment, UUID> {
    fun findByStatus(status: ShipmentStatus): List<Shipment>
    fun findByStatusNot(status: ShipmentStatus): List<Shipment>

    @Modifying
    @Query(value = "DELETE FROM shipments WHERE deleted_at IS NOT NULL AND deleted_at < :cutoff", nativeQuery = true)
    fun purgeDeletedBefore(cutoff: OffsetDateTime): Int
}
