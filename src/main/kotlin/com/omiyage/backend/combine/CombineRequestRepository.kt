package com.omiyage.backend.combine

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface CombineRequestRepository : JpaRepository<CombineRequest, UUID> {
    fun existsByRequesterOrder_IdAndTargetOrder_IdAndStatus(requesterOrderId: UUID, targetOrderId: UUID, status: CombineRequestStatus): Boolean
    fun findByTargetOrder_UserIdAndStatus(targetUserId: UUID, status: CombineRequestStatus): List<CombineRequest>
    fun findByRequesterOrder_UserIdAndStatus(requesterUserId: UUID, status: CombineRequestStatus): List<CombineRequest>
    fun findByStatus(status: CombineRequestStatus): List<CombineRequest>
}
