package com.omiyage.backend.combine

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/admin/combine-requests")
class AdminCombineRequestController(private val service: CombineRequestService) {

    @GetMapping
    fun listPending(): List<CombineRequestDto> = service.listAllPending()

    @PostMapping("/{id}/force-accept")
    fun forceAccept(@PathVariable id: UUID): CombineRequestDto = service.forceAccept(id)
}
