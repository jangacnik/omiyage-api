package com.omiyage.backend.catalog

import com.omiyage.backend.web.PageResponse
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/inventory")
class InventoryController(private val service: InventoryService) {

    @GetMapping
    fun search(
        @RequestParam(required = false) title: String?,
        @RequestParam(required = false) categoryId: UUID?,
        @PageableDefault(size = 20) pageable: Pageable,
    ): PageResponse<InventoryItemDto> = service.search(title, categoryId, InventoryStatus.ACTIVE, pageable)

    @GetMapping("/{id}")
    fun getById(@PathVariable id: UUID): InventoryItemDto = service.getById(id)
}
