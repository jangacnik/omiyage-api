package com.omiyage.backend.shipment

import com.omiyage.backend.web.getOrRespond
import jakarta.validation.Valid
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
@RequestMapping("/api/admin/shipping-rates")
class AdminShippingRateController(private val service: ShippingRateService) {

    @GetMapping
    fun list(): List<ShippingRateDto> = service.listAll()

    @PostMapping
    fun create(@Valid @RequestBody request: ShippingRateRequest): ResponseEntity<ShippingRateDto> =
        ResponseEntity.status(HttpStatus.CREATED).body(service.create(request).getOrRespond())

    @PatchMapping("/{id}")
    fun update(@PathVariable id: UUID, @Valid @RequestBody request: ShippingRateRequest): ShippingRateDto =
        service.update(id, request).getOrRespond()

    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: UUID): ResponseEntity<Void> {
        service.delete(id).getOrRespond()
        return ResponseEntity.noContent().build()
    }
}
