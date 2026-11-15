package com.omiyage.backend.catalog

import arrow.core.Either
import arrow.core.raise.ensure
import com.omiyage.backend.errors.CatalogError
import com.omiyage.backend.errors.DomainError
import com.omiyage.backend.common.transactionalEither
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class CategoryService(private val repository: CategoryRepository) {

    @Transactional(readOnly = true)
    fun list(): List<CategoryDto> = repository.findAll().sortedBy { it.name }.map { it.toDto() }

    @Transactional
    fun create(name: String, slug: String): Either<DomainError, CategoryDto> = transactionalEither {
        ensure(!repository.existsBySlug(slug)) { CatalogError.CategorySlugInUse }
        repository.save(Category(name = name, slug = slug)).toDto()
    }

    @Transactional
    fun delete(id: UUID): Either<DomainError, Unit> = transactionalEither {
        ensure(repository.existsById(id)) { CatalogError.CategoryNotFound }
        repository.deleteById(id)
    }
}
