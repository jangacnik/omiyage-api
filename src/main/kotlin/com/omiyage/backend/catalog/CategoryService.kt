package com.omiyage.backend.catalog

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import org.springframework.http.HttpStatus
import java.util.UUID

@Service
class CategoryService(private val repository: CategoryRepository) {

    @Transactional(readOnly = true)
    fun list(): List<CategoryDto> = repository.findAll().sortedBy { it.name }.map { it.toDto() }

    @Transactional
    fun create(name: String, slug: String): CategoryDto {
        if (repository.existsBySlug(slug)) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "slug already in use")
        }
        return repository.save(Category(name = name, slug = slug)).toDto()
    }

    @Transactional
    fun delete(id: UUID) {
        if (!repository.existsById(id)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "category not found")
        }
        repository.deleteById(id)
    }
}
