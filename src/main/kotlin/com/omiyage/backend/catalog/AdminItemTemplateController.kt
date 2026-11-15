package com.omiyage.backend.catalog

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
@RequestMapping("/api/admin/item-templates")
class AdminItemTemplateController(private val service: ItemTemplateService) {

    @GetMapping
    fun list(): List<ItemTemplateDto> = service.listAll()

    @PostMapping
    fun create(@Valid @RequestBody request: ItemTemplateRequest): ResponseEntity<ItemTemplateDto> =
        ResponseEntity.status(HttpStatus.CREATED).body(service.create(request))

    @PatchMapping("/{id}")
    fun update(@PathVariable id: UUID, @Valid @RequestBody request: ItemTemplateRequest): ItemTemplateDto =
        service.update(id, request).getOrRespond()

    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: UUID): ResponseEntity<Void> {
        service.delete(id).getOrRespond()
        return ResponseEntity.noContent().build()
    }
}
