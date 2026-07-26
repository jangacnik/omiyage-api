package com.omiyage.backend.order

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface OrderRepository : JpaRepository<Order, UUID> {
    fun findByUserId(userId: UUID, pageable: Pageable): Page<Order>
    fun findByStatus(status: OrderStatus, pageable: Pageable): Page<Order>
}

interface OrderItemRepository : JpaRepository<OrderItem, UUID>
