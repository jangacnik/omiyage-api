package com.omiyage.backend.combine

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface CombineRequestRepository : JpaRepository<CombineRequest, UUID> {
    fun existsByRequesterOrder_IdAndTargetShipment_IdAndStatus(requesterOrderId: UUID, targetShipmentId: UUID, status: CombineRequestStatus): Boolean
    fun findByTargetShipment_OwnerUserIdAndStatus(ownerUserId: UUID, status: CombineRequestStatus): List<CombineRequest>
    fun findByRequesterOrder_UserIdAndStatus(requesterUserId: UUID, status: CombineRequestStatus): List<CombineRequest>
    fun findByStatus(status: CombineRequestStatus): List<CombineRequest>
}
