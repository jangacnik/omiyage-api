package com.omiyage.backend.shipment

import com.omiyage.backend.security.CurrentUser
import com.omiyage.backend.user.MeishiNicknameClient
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@RestController
@RequestMapping("/api/shipments/mine")
class MemberShipmentController(
    private val service: ShipmentService,
    private val exchangeRates: ExchangeRateService,
    private val meishiNicknames: MeishiNicknameClient,
) {

    // Degrades to an empty map (frontend falls back to a shortened userId) if Meishi is
    // unreachable - same convention as ShipmentService.browsableForMember.
    private fun nicknamesByUserId(authHeader: String): Map<UUID, String?> = try {
        meishiNicknames.nicknames(authHeader).associate { it.id to it.nickname }
    } catch (e: Exception) {
        emptyMap()
    }

    @GetMapping
    fun listMine(@RequestHeader("Authorization") authHeader: String): List<MemberShipmentDto> {
        val userId = CurrentUser.principal().id
        val nicknames = nicknamesByUserId(authHeader)
        return service.listAll()
            .filter { shipment -> shipment.orders.any { it.userId == userId } }
            .map { it.toMemberDto(exchangeRates::toEur, nicknames) }
    }

    @GetMapping("/{id}")
    fun getMine(@PathVariable id: UUID, @RequestHeader("Authorization") authHeader: String): MemberShipmentDto {
        val userId = CurrentUser.principal().id
        val shipment = service.getById(id)
        if (shipment.orders.none { it.userId == userId }) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "shipment not found")
        }
        return shipment.toMemberDto(exchangeRates::toEur, nicknamesByUserId(authHeader))
    }

    @PatchMapping("/{id}/title")
    fun renameMine(
        @PathVariable id: UUID,
        @Valid @RequestBody request: RenameShipmentRequest,
        @RequestHeader("Authorization") authHeader: String,
    ): MemberShipmentDto =
        service.renameMine(id, CurrentUser.principal().id, request.title).toMemberDto(exchangeRates::toEur, nicknamesByUserId(authHeader))

    @PostMapping("/{id}/mark-arrived")
    fun markArrivedMine(@PathVariable id: UUID, @RequestHeader("Authorization") authHeader: String): MemberShipmentDto =
        service.markArrivedMine(id, CurrentUser.principal().id).toMemberDto(exchangeRates::toEur, nicknamesByUserId(authHeader))
}
