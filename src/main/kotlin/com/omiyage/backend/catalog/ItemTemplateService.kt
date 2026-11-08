package com.omiyage.backend.catalog

import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import com.omiyage.backend.errors.CatalogError
import com.omiyage.backend.errors.DomainError
import com.omiyage.backend.errors.transactionalEither
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.jvm.optionals.getOrNull

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
    fun update(id: UUID, request: ItemTemplateRequest): Either<DomainError, ItemTemplateDto> = transactionalEither {
        val template = findOrRaise(id)
        template.name = request.name
        template.title = request.title
        template.description = request.description
        template.priceAmount = request.priceAmount
        template.boughtPrice = request.boughtPrice
        template.weight = request.weight
        template.toDto()
    }

    @Transactional
    fun delete(id: UUID): Either<DomainError, Unit> = transactionalEither {
        ensure(templates.existsById(id)) { CatalogError.ItemTemplateNotFound }
        templates.deleteById(id)
    }

    private fun Raise<DomainError>.findOrRaise(id: UUID): ItemTemplate =
        ensureNotNull(templates.findById(id).getOrNull()) { CatalogError.ItemTemplateNotFound }
}
