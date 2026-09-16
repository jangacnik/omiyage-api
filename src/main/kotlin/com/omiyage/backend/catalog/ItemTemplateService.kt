package com.omiyage.backend.catalog

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@Service
class ItemTemplateService(private val templates: ItemTemplateRepository) {

    @Transactional(readOnly = true)
    fun listAll(): List<ItemTemplateDto> = templates.findAllByOrderByNameAsc().map { it.toDto() }

    @Transactional
    fun create(request: ItemTemplateRequest): ItemTemplateDto = templates.save(
        ItemTemplate(
            name = request.name,
            title = request.title,
            description = request.description,
            priceAmount = request.priceAmount,
            boughtPrice = request.boughtPrice,
            weight = request.weight,
        ),
    ).toDto()

    @Transactional
    fun update(id: UUID, request: ItemTemplateRequest): ItemTemplateDto {
        val template = findOrThrow(id)
        template.name = request.name
        template.title = request.title
        template.description = request.description
        template.priceAmount = request.priceAmount
        template.boughtPrice = request.boughtPrice
        template.weight = request.weight
        return template.toDto()
    }

    @Transactional
    fun delete(id: UUID) {
        if (!templates.existsById(id)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "item template not found")
        }
        templates.deleteById(id)
    }

    private fun findOrThrow(id: UUID): ItemTemplate =
        templates.findById(id).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "item template not found") }
}
