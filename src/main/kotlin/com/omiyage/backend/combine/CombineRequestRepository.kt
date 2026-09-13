package com.omiyage.backend.combine

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.time.OffsetDateTime
import java.util.UUID

interface CombineRequestRepository : JpaRepository<CombineRequest, UUID> {
    fun existsByRequesterOrder_IdAndTargetShipment_IdAndStatus(requesterOrderId: UUID, targetShipmentId: UUID, status: CombineRequestStatus): Boolean
    fun findByTargetShipment_OwnerUserIdAndStatus(ownerUserId: UUID, status: CombineRequestStatus): List<CombineRequest>
    fun findByRequesterOrder_UserIdAndStatus(requesterUserId: UUID, status: CombineRequestStatus): List<CombineRequest>
    fun findByStatus(status: CombineRequestStatus): List<CombineRequest>

    // Both FK columns are NOT NULL with no ON DELETE clause, so a combine_requests row left
    // pointing at an order/shipment the purge job is about to hard-delete would FK-violate and
    // roll back the whole purge transaction (orders/shipments purge included) every night from
    // then on. Must run before orders/shipments purgeDeletedBefore in the same job.
    @Modifying
    @Query(
        value = """
            DELETE FROM combine_requests
            WHERE requester_order_id IN (SELECT id FROM orders WHERE deleted_at IS NOT NULL AND deleted_at < :cutoff)
               OR target_shipment_id IN (SELECT id FROM shipments WHERE deleted_at IS NOT NULL AND deleted_at < :cutoff)
        """,
        nativeQuery = true,
    )
    fun purgeReferencingPurgeableOrdersOrShipments(cutoff: OffsetDateTime): Int
}
