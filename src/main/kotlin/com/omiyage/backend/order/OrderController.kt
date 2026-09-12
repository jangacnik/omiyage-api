package com.omiyage.backend.order

import com.omiyage.backend.security.CurrentUser
import com.omiyage.backend.web.PageResponse
import jakarta.validation.Valid
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/orders")
class OrderController(private val service: OrderService) {

    @PostMapping
    fun create(@Valid @RequestBody request: CreateOrderRequest): ResponseEntity<OrderDto> =
        ResponseEntity.status(HttpStatus.CREATED).body(service.create(CurrentUser.principal(), request))

    @GetMapping("/mine")
    fun listMine(@PageableDefault(size = 20) pageable: Pageable): PageResponse<OrderDto> =
        service.listMine(CurrentUser.principal(), pageable)

    @GetMapping("/{id}")
    fun getById(@PathVariable id: UUID): OrderDto = service.getById(id, CurrentUser.principal())

    @PostMapping("/merge")
    fun mergeMine(@Valid @RequestBody request: MergeOrdersRequest): OrderDto =
        service.mergeMine(CurrentUser.principal(), request.orderIds)

    @PatchMapping("/{id}/nickname")
    fun updateNickname(
        @PathVariable id: UUID,
        @Valid @RequestBody request: UpdateOrderNicknameRequest,
    ): OrderDto = service.updateNickname(id, CurrentUser.principal(), request.nickname)

    @PostMapping("/{id}/items")
    fun addOwnItem(
        @PathVariable id: UUID,
        @Valid @RequestBody request: CreateOrderItemRequest,
    ): ResponseEntity<OrderDto> =
        ResponseEntity.status(HttpStatus.CREATED).body(service.addOwnItem(id, CurrentUser.principal(), request))

    @PatchMapping("/{id}/items/{itemId}")
    fun updateOwnItem(
        @PathVariable id: UUID,
        @PathVariable itemId: UUID,
        @Valid @RequestBody request: UpdateOwnOrderItemRequest,
    ): OrderDto = service.updateOwnItem(id, itemId, CurrentUser.principal(), request)

    @DeleteMapping("/{id}/items/{itemId}")
    fun removeOwnItem(@PathVariable id: UUID, @PathVariable itemId: UUID): OrderDto =
        service.removeOwnItem(id, itemId, CurrentUser.principal())
}
