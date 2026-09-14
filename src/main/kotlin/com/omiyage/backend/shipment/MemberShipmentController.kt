package com.omiyage.backend.shipment

import com.omiyage.backend.security.CurrentUser
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@RestController
@RequestMapping("/api/shipments/mine")
class MemberShipmentController(private val service: ShipmentService, private val exchangeRates: ExchangeRateService) {

    @GetMapping
    fun listMine(): List<MemberShipmentDto> {
        val userId = CurrentUser.principal().id
        return service.listAll()
            .filter { shipment -> shipment.orders.any { it.userId == userId } }
            .map { it.toMemberDto(exchangeRates::toEur) }
    }

    @GetMapping("/{id}")
    fun getMine(@PathVariable id: UUID): MemberShipmentDto {
        val userId = CurrentUser.principal().id
        val shipment = service.getById(id)
        if (shipment.orders.none { it.userId == userId }) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "shipment not found")
        }
        return shipment.toMemberDto(exchangeRates::toEur)
    }

    @PatchMapping("/{id}/title")
    fun renameMine(@PathVariable id: UUID, @Valid @RequestBody request: RenameShipmentRequest): MemberShipmentDto =
        service.renameMine(id, CurrentUser.principal().id, request.title).toMemberDto(exchangeRates::toEur)

    @PostMapping("/{id}/mark-arrived")
    fun markArrivedMine(@PathVariable id: UUID): MemberShipmentDto =
        service.markArrivedMine(id, CurrentUser.principal().id).toMemberDto(exchangeRates::toEur)
}
