package com.omiyage.backend.shipment

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
@RequestMapping("/api/admin/shipments")
class AdminShipmentController(private val service: ShipmentService) {

    @GetMapping
    fun list(): List<ShipmentDto> = service.listAll()

    @GetMapping("/{id}")
    fun getById(@PathVariable id: UUID): ShipmentDto = service.getById(id)

    @PostMapping
    fun create(@Valid @RequestBody request: CreateShipmentRequest): ResponseEntity<ShipmentDto> =
        ResponseEntity.status(HttpStatus.CREATED).body(service.create(request))

    @PatchMapping("/{id}")
    fun update(@PathVariable id: UUID, @Valid @RequestBody request: UpdateShipmentRequest): ShipmentDto =
        service.update(id, request)

    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: UUID): ResponseEntity<Void> {
        service.adminDelete(id)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/combine")
    fun combineShipments(@Valid @RequestBody request: CombineShipmentsRequest): ShipmentDto =
        service.combineShipments(request.shipmentIds, request.title)

    @PostMapping("/{id}/split")
    fun split(@PathVariable id: UUID, @Valid @RequestBody request: SplitShipmentRequest): ResponseEntity<ShipmentDto> =
        ResponseEntity.status(HttpStatus.CREATED).body(service.splitOff(id, request.orderIds, request.title))
}
