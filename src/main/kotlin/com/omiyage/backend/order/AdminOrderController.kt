package com.omiyage.backend.order

import com.omiyage.backend.web.getOrRespond
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
        ResponseEntity.status(HttpStatus.CREATED).body(service.adminCreate(userId, request).getOrRespond())

    @GetMapping
    fun list(
        @RequestParam(required = false) status: OrderStatus?,
        @RequestParam(required = false, defaultValue = "") excludeStatus: Set<OrderStatus>,
        @RequestParam(required = false, defaultValue = "UPDATED_AT") sortBy: AdminOrderSortBy,
        @RequestParam(required = false, defaultValue = "DESC") direction: Sort.Direction,
        @PageableDefault(size = 20) pageable: Pageable,
        @RequestHeader("Authorization") authHeader: String,
    ): PageResponse<OrderDto> = service.adminList(status, excludeStatus, sortBy, direction, pageable, authHeader)

    @PatchMapping("/{id}")
    fun updateStatus(@PathVariable id: UUID, @Valid @RequestBody request: UpdateOrderStatusRequest): OrderDto =
        service.updateStatus(id, request.status).getOrRespond()

    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: UUID): ResponseEntity<Void> {
        service.adminDelete(id).getOrRespond()
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/{id}/items")
    fun addItem(@PathVariable id: UUID, @Valid @RequestBody request: CreateOrderItemRequest): ResponseEntity<OrderDto> =
        ResponseEntity.status(HttpStatus.CREATED).body(service.adminAddItem(id, request).getOrRespond())

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
    ).getOrRespond()

    @PatchMapping("/{id}/items/{itemId}/details")
    fun updateItemContent(
        @PathVariable id: UUID,
        @PathVariable itemId: UUID,
        @Valid @RequestBody request: UpdateOwnOrderItemRequest,
    ): OrderDto = service.adminUpdateItemContent(id, itemId, request).getOrRespond()

    @DeleteMapping("/{id}/items/{itemId}")
    fun removeItem(@PathVariable id: UUID, @PathVariable itemId: UUID): OrderDto =
        service.adminRemoveItem(id, itemId).getOrRespond()

    @PostMapping("/{id}/items/{itemId}/add-to-inventory")
    fun addItemToInventory(@PathVariable id: UUID, @PathVariable itemId: UUID): ResponseEntity<OrderDto> =
        ResponseEntity.status(HttpStatus.CREATED).body(service.adminAddItemToInventory(id, itemId).getOrRespond())

    @PatchMapping("/{id}/items/{itemId}/paid")
    fun markItemPaid(
        @PathVariable id: UUID,
        @PathVariable itemId: UUID,
        @Valid @RequestBody request: MarkItemPaidRequest,
    ): OrderDto = service.adminMarkItemPaid(id, itemId, request.paid).getOrRespond()

    @PatchMapping("/{id}/paid")
    fun markOrderPaid(@PathVariable id: UUID, @Valid @RequestBody request: MarkItemPaidRequest): OrderDto =
        service.adminMarkOrderPaid(id, request.paid).getOrRespond()

    @PatchMapping("/{id}/shipment")
    fun assignShipment(@PathVariable id: UUID, @RequestBody request: AssignShipmentRequest): OrderDto =
        shipments.assignOrder(id, request.shipmentId).getOrRespond()

    @PostMapping("/merge")
    fun merge(@Valid @RequestBody request: MergeOrdersRequest): OrderDto = service.adminMerge(request.orderIds).getOrRespond()

    @PatchMapping("/{id}/nickname")
    fun updateNickname(@PathVariable id: UUID, @Valid @RequestBody request: UpdateOrderNicknameRequest): OrderDto =
        service.adminUpdateNickname(id, request.nickname).getOrRespond()

    @GetMapping("/by-user/{userId}")
    fun listForUser(@PathVariable userId: UUID): List<OrderDto> = service.adminOrdersForUser(userId)

    @PostMapping("/{id}/items/move")
    fun moveItems(@PathVariable id: UUID, @Valid @RequestBody request: MoveOrderItemsRequest): MoveItemsResponseDto =
        service.adminMoveItems(id, request.itemIds, request.targetOrderId).getOrRespond()
}
