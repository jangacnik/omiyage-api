package com.omiyage.backend.basket

import com.omiyage.backend.order.OrderDto
import com.omiyage.backend.security.CurrentUser
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/basket")
class BasketController(private val service: BasketService) {

    @GetMapping
    fun list(): List<BasketLineDto> = service.list(CurrentUser.principal())

    @PostMapping("/lines")
    fun add(@Valid @RequestBody request: AddBasketLineRequest): ResponseEntity<BasketLineDto> =
        ResponseEntity.status(HttpStatus.CREATED).body(service.add(CurrentUser.principal(), request))

    @PutMapping("/lines/{id}")
    fun update(@PathVariable id: UUID, @Valid @RequestBody request: UpdateBasketLineRequest): BasketLineDto =
        service.update(CurrentUser.principal(), id, request)

    @DeleteMapping("/lines/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun remove(@PathVariable id: UUID) = service.remove(CurrentUser.principal(), id)

    @PostMapping("/confirm")
    fun confirm(): OrderDto = service.confirm(CurrentUser.principal())

    @PostMapping("/add-to-order")
    fun addToOrder(@Valid @RequestBody request: AddBasketToOrderRequest): OrderDto =
        service.addToOrder(CurrentUser.principal(), request.orderId!!)

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun clear() = service.clear(CurrentUser.principal())
}
