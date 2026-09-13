package com.omiyage.backend.catalog

import com.omiyage.backend.web.PageResponse
import jakarta.validation.Valid
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/admin/inventory")
class AdminInventoryController(private val service: InventoryService) {

    @GetMapping
    fun search(
        @RequestParam(required = false) title: String?,
        @RequestParam(required = false) categoryId: UUID?,
        @RequestParam(required = false) status: InventoryStatus?,
        @PageableDefault(size = 20) pageable: Pageable,
    ): PageResponse<InventoryItemDto> = service.search(title, categoryId, status, pageable, includeCost = true)

    @PostMapping
    fun create(@Valid @RequestBody request: InventoryItemRequest): ResponseEntity<InventoryItemDto> =
        ResponseEntity.status(HttpStatus.CREATED).body(service.create(request))

    @PutMapping("/{id}")
    fun update(@PathVariable id: UUID, @Valid @RequestBody request: InventoryItemRequest): InventoryItemDto =
        service.update(id, request)

    @PatchMapping("/{id}/status")
    fun updateStatus(@PathVariable id: UUID, @Valid @RequestBody request: UpdateStatusRequest): InventoryItemDto =
        service.updateStatus(id, request.status)

    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: UUID): ResponseEntity<Void> {
        service.delete(id)
        return ResponseEntity.noContent().build()
    }
}
