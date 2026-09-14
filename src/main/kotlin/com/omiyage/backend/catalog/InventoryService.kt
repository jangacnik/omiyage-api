package com.omiyage.backend.catalog

import com.omiyage.backend.common.Currency
import com.omiyage.backend.order.OrderItemRepository
import com.omiyage.backend.shipment.ExchangeRateService
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
    private val orderItems: OrderItemRepository,
    private val exchangeRates: ExchangeRateService,
) {

    @Transactional(readOnly = true)
    fun search(
        title: String?,
        categoryId: UUID?,
        status: InventoryStatus?,
        pageable: Pageable,
        includeCost: Boolean = false,
        inStockOnly: Boolean = false,
    ): PageResponse<InventoryItemDto> =
        items.search(title?.trim().orEmpty(), categoryId, status, inStockOnly, pageable)
            .toPageResponse { it.toDto(includeCost, exchangeRates::toEur) }

    @Transactional(readOnly = true)
    fun getById(id: UUID, includeCost: Boolean = false): InventoryItemDto =
        findOrThrow(id).toDto(includeCost, exchangeRates::toEur)

    // Listed price is always EUR (customer-facing) and bought price is always JPY (that's where
    // the admin actually buys) - fixed by policy, not a per-item choice, same convention as
    // OrderService's item prices.
    @Transactional
    fun create(request: InventoryItemRequest): InventoryItemDto {
        requirePreorderDate(request)
        val item = InventoryItem(
            title = request.title,
            description = request.description,
            link = request.link,
            condition = request.condition,
            priceAmount = request.priceAmount,
            currency = Currency.EUR,
            boughtPrice = request.boughtPrice,
            stockQuantity = request.stockQuantity,
            isPreorder = request.isPreorder,
            expectedReleaseDate = request.expectedReleaseDate,
        )
        applyImagesAndCategories(item, request)
        applyTags(item, request)
        return items.save(item).toDto(includeCost = true, toEur = exchangeRates::toEur)
    }

    @Transactional
    fun update(id: UUID, request: InventoryItemRequest): InventoryItemDto {
        requirePreorderDate(request)
        val item = findOrThrow(id)
        item.title = request.title
        item.description = request.description
        item.link = request.link
        item.condition = request.condition
        item.priceAmount = request.priceAmount
        item.currency = Currency.EUR
        item.boughtPrice = request.boughtPrice
        item.stockQuantity = request.stockQuantity
        item.isPreorder = request.isPreorder
        item.expectedReleaseDate = request.expectedReleaseDate
        item.updatedAt = OffsetDateTime.now()
        item.images.clear()
        item.categories.clear()
        applyImagesAndCategories(item, request)
        applyTags(item, request)
        return item.toDto(includeCost = true, toEur = exchangeRates::toEur)
    }

    private fun requirePreorderDate(request: InventoryItemRequest) {
        if (request.isPreorder && request.expectedReleaseDate == null) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "a preorder item must have an expected release date")
        }
    }

    @Transactional
    fun updateStatus(id: UUID, status: InventoryStatus): InventoryItemDto {
        val item = findOrThrow(id)
        item.status = status
        item.updatedAt = OffsetDateTime.now()
        return item.toDto(includeCost = true, toEur = exchangeRates::toEur)
    }

    @Transactional
    fun delete(id: UUID) {
        if (!items.existsById(id)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "inventory item not found")
        }
        // Soft-deleting the item makes it invisible to @SQLRestriction, so any order item still
        // pointing at it would fail to load (EntityNotFoundException) unless detached first - the
        // real DB's ON DELETE SET NULL only fires on a hard delete, which this no longer is.
        orderItems.detachInventoryItem(id)
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

    private fun applyTags(item: InventoryItem, request: InventoryItemRequest) {
        item.tags.clear()
        item.tags.addAll(request.tags.map { it.trim() }.filter { it.isNotBlank() })
    }

    private fun findOrThrow(id: UUID): InventoryItem =
        items.findById(id).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "inventory item not found") }
}
