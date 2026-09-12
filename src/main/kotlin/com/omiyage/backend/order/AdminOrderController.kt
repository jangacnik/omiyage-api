package com.omiyage.backend.order

import com.omiyage.backend.shipment.AssignShipmentRequest
import com.omiyage.backend.shipment.ShipmentService
import jakarta.validation.Valid
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/admin/orders")
class AdminOrderController(private val service: OrderService, private val shipments: ShipmentService) {

    @GetMapping
    fun list(
        @RequestParam(required = false) status: OrderStatus?,
        @PageableDefault(size = 20) pageable: Pageable,
    ): PageResponse<OrderDto> = service.adminList(status, pageable)

    @PatchMapping("/{id}")
    fun updateStatus(@PathVariable id: UUID, @Valid @RequestBody request: UpdateOrderStatusRequest): OrderDto =
        service.updateStatus(id, request.status)

    @PatchMapping("/{id}/items/{itemId}")
    fun updateItem(
        @PathVariable id: UUID,
        @PathVariable itemId: UUID,
        @Valid @RequestBody request: UpdateOrderItemRequest,
    ): OrderDto = service.updateItem(id, itemId, request.status, request.actualPrice, request.weight)

    @PatchMapping("/{id}/shipment")
    fun assignShipment(@PathVariable id: UUID, @RequestBody request: AssignShipmentRequest): OrderDto =
        shipments.assignOrder(id, request.shipmentId)
}
