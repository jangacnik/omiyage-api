package com.omiyage.backend.order

import com.omiyage.backend.shipment.AssignShipmentRequest
import com.omiyage.backend.shipment.ShipmentService
import com.omiyage.backend.web.PageResponse
import jakarta.validation.Valid
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.web.PageableDefault
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/admin/orders")
class AdminOrderController(private val service: OrderService, private val shipments: ShipmentService) {

    @PostMapping("/for-user/{userId}")
    fun create(@PathVariable userId: UUID, @Valid @RequestBody request: CreateOrderRequest): ResponseEntity<OrderDto> =
        ResponseEntity.status(HttpStatus.CREATED).body(service.adminCreate(userId, request))

    @GetMapping
    fun list(
        @RequestParam(required = false) status: OrderStatus?,
        @RequestParam(required = false, defaultValue = "UPDATED_AT") sortBy: AdminOrderSortBy,
        @RequestParam(required = false, defaultValue = "DESC") direction: Sort.Direction,
        @PageableDefault(size = 20) pageable: Pageable,
        @RequestHeader("Authorization") authHeader: String,
    ): PageResponse<OrderDto> = service.adminList(status, sortBy, direction, pageable, authHeader)

    @PatchMapping("/{id}")
    fun updateStatus(@PathVariable id: UUID, @Valid @RequestBody request: UpdateOrderStatusRequest): OrderDto =
        service.updateStatus(id, request.status)

    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: UUID): ResponseEntity<Void> {
        service.adminDelete(id)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/{id}/items")
    fun addItem(@PathVariable id: UUID, @Valid @RequestBody request: CreateOrderItemRequest): ResponseEntity<OrderDto> =
        ResponseEntity.status(HttpStatus.CREATED).body(service.adminAddItem(id, request))

    @PatchMapping("/{id}/items/{itemId}")
    fun updateItem(
        @PathVariable id: UUID,
        @PathVariable itemId: UUID,
        @Valid @RequestBody request: UpdateOrderItemRequest,
    ): OrderDto = service.updateItem(
        orderId = id,
        itemId = itemId,
        status = request.status,
        actualPrice = request.actualPrice,
        soldPrice = request.soldPrice,
        weight = request.weight,
    )

    @PatchMapping("/{id}/items/{itemId}/details")
    fun updateItemContent(
        @PathVariable id: UUID,
        @PathVariable itemId: UUID,
        @Valid @RequestBody request: UpdateOwnOrderItemRequest,
    ): OrderDto = service.adminUpdateItemContent(id, itemId, request)

    @DeleteMapping("/{id}/items/{itemId}")
    fun removeItem(@PathVariable id: UUID, @PathVariable itemId: UUID): OrderDto =
        service.adminRemoveItem(id, itemId)

    @PatchMapping("/{id}/items/{itemId}/paid")
    fun markItemPaid(
        @PathVariable id: UUID,
        @PathVariable itemId: UUID,
        @Valid @RequestBody request: MarkItemPaidRequest,
    ): OrderDto = service.adminMarkItemPaid(id, itemId, request.paid)

    @PatchMapping("/{id}/shipment")
    fun assignShipment(@PathVariable id: UUID, @RequestBody request: AssignShipmentRequest): OrderDto =
        shipments.assignOrder(id, request.shipmentId)
}
