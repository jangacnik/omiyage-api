package com.omiyage.backend.catalog

import com.omiyage.backend.web.PageResponse
import com.omiyage.backend.web.toPageResponse
import org.springframework.data.domain.Pageable
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.OffsetDateTime
import java.util.UUID

@Service
class InventoryService(
    private val items: InventoryItemRepository,
    private val categories: CategoryRepository,
) {

    @Transactional(readOnly = true)
    fun search(title: String?, categoryId: UUID?, status: InventoryStatus?, pageable: Pageable): PageResponse<InventoryItemDto> =
        items.search(title?.trim().orEmpty(), categoryId, status, pageable).toPageResponse { it.toDto() }

    @Transactional(readOnly = true)
    fun getById(id: UUID): InventoryItemDto = findOrThrow(id).toDto()

    @Transactional
    fun create(request: InventoryItemRequest): InventoryItemDto {
        val item = InventoryItem(
            title = request.title,
            description = request.description,
            condition = request.condition,
            priceAmount = request.priceAmount,
            currency = request.currency,
            stockQuantity = request.stockQuantity,
        )
        applyImagesAndCategories(item, request)
        return items.save(item).toDto()
    }

    @Transactional
    fun update(id: UUID, request: InventoryItemRequest): InventoryItemDto {
        val item = findOrThrow(id)
        item.title = request.title
        item.description = request.description
        item.condition = request.condition
        item.priceAmount = request.priceAmount
        item.currency = request.currency
        item.stockQuantity = request.stockQuantity
        item.updatedAt = OffsetDateTime.now()
        item.images.clear()
        item.categories.clear()
        applyImagesAndCategories(item, request)
        return item.toDto()
    }

    @Transactional
    fun updateStatus(id: UUID, status: InventoryStatus): InventoryItemDto {
        val item = findOrThrow(id)
        item.status = status
        item.updatedAt = OffsetDateTime.now()
        return item.toDto()
    }

    @Transactional
    fun delete(id: UUID) {
        if (!items.existsById(id)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "inventory item not found")
        }
        items.deleteById(id)
    }

    private fun applyImagesAndCategories(item: InventoryItem, request: InventoryItemRequest) {
        request.imageKeys.forEachIndexed { index, key ->
            item.images.add(InventoryItemImage(inventoryItem = item, objectKey = key, sortOrder = index))
        }
        if (request.categoryIds.isNotEmpty()) {
            val found = categories.findAllById(request.categoryIds)
            if (found.size != request.categoryIds.toSet().size) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "one or more categoryIds do not exist")
            }
            item.categories.addAll(found)
        }
    }

    private fun findOrThrow(id: UUID): InventoryItem =
        items.findById(id).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "inventory item not found") }
}
