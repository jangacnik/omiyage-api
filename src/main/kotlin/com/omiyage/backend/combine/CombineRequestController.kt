package com.omiyage.backend.combine

import com.omiyage.backend.security.CurrentUser
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/orders/combine-requests")
class CombineRequestController(private val service: CombineRequestService) {

    @PostMapping
    fun create(@Valid @RequestBody request: CreateCombineRequestRequest): ResponseEntity<CombineRequestDto> =
        ResponseEntity.status(HttpStatus.CREATED)
            .body(service.create(CurrentUser.principal(), request.requesterOrderId, request.targetOrderId))

    @GetMapping("/incoming")
    fun incoming(): List<CombineRequestDto> = service.listIncoming(CurrentUser.principal())

    @GetMapping("/outgoing")
    fun outgoing(): List<CombineRequestDto> = service.listOutgoing(CurrentUser.principal())

    @PostMapping("/{id}/accept")
    fun accept(@PathVariable id: UUID): CombineRequestDto = service.accept(CurrentUser.principal(), id)

    @PostMapping("/{id}/decline")
    fun decline(@PathVariable id: UUID): CombineRequestDto = service.decline(CurrentUser.principal(), id)

    @DeleteMapping("/{id}")
    fun cancel(@PathVariable id: UUID): CombineRequestDto = service.cancel(CurrentUser.principal(), id)
}
