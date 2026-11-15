package com.omiyage.backend.catalog

import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import com.omiyage.backend.common.Currency
import com.omiyage.backend.errors.CatalogError
import com.omiyage.backend.errors.DomainError
import com.omiyage.backend.common.transactionalEither
import com.omiyage.backend.media.MediaService
import com.omiyage.backend.order.OrderItemRepository
import com.omiyage.backend.shipment.ExchangeRateService
import com.omiyage.backend.web.PageResponse
import com.omiyage.backend.web.toPageResponse
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.jvm.optionals.getOrNull

private const val MAX_TAG_LENGTH = 50

@Service
class InventoryService(
    private val items: InventoryItemRepository,
    private val categories: CategoryRepository,
    private val orderItems: OrderItemRepository,
    private val exchangeRates: ExchangeRateService,
    private val media: MediaService,
) {

    private val events = org.slf4j.LoggerFactory.getLogger("events")

    @Transactional(readOnly = true)
    fun search(
        title: String?,
        categoryId: UUID?,
        status: InventoryStatus?,
        pageable: Pageable,
        includeCost: Boolean = false,
        inStockOnly: Boolean = false,
    ): PageResponse<InventoryItemDto> =
        items.search(title?.trim().orEmpty(), categoryId, status, inStockOnly, withIdTiebreak(pageable))
            .toPageResponse { it.toDto(includeCost, exchangeRates::toEur) }

    @Transactional(readOnly = true)
    fun getById(id: UUID, includeCost: Boolean = false): Either<DomainError, InventoryItemDto> = either {
        findOrRaise(id).toDto(includeCost, exchangeRates::toEur)
    }

    // Listed price is always EUR (customer-facing) and bought price is always JPY (that's where
    // the admin actually buys) - fixed by policy, not a per-item choice, same convention as
    // OrderService's item prices.
    @Transactional
    fun create(request: InventoryItemRequest): Either<DomainError, InventoryItemDto> = transactionalEither {
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
            weight = request.weight,
            isPreorder = request.isPreorder,
            expectedReleaseDate = request.expectedReleaseDate,
        )
        applyImagesAndCategories(item, request)
        applyTags(item, request)
        val saved = items.save(item)
        events.info("inventory_item_created itemId={} stockQuantity={}", saved.id, saved.stockQuantity)
        saved.toDto(includeCost = true, toEur = exchangeRates::toEur)
    }

    @Transactional
    fun update(id: UUID, request: InventoryItemRequest): Either<DomainError, InventoryItemDto> = transactionalEither {
        requirePreorderDate(request)
        val item = findOrRaise(id)
        if (item.stockQuantity != request.stockQuantity) {
            events.info(
                "inventory_stock_changed itemId={} delta={} reason=admin_update",
                id, request.stockQuantity - item.stockQuantity,
            )
        }
        item.title = request.title
        item.description = request.description
        item.link = request.link
        item.condition = request.condition
        item.priceAmount = request.priceAmount
        item.currency = Currency.EUR
        item.boughtPrice = request.boughtPrice
        item.stockQuantity = request.stockQuantity
        item.weight = request.weight
        item.isPreorder = request.isPreorder
        item.expectedReleaseDate = request.expectedReleaseDate
        item.updatedAt = OffsetDateTime.now()
        item.images.clear()
        item.categories.clear()
        applyImagesAndCategories(item, request)
        applyTags(item, request)
        item.toDto(includeCost = true, toEur = exchangeRates::toEur)
    }

    private fun Raise<DomainError>.requirePreorderDate(request: InventoryItemRequest) {
        ensure(!request.isPreorder || request.expectedReleaseDate != null) { CatalogError.PreorderNeedsReleaseDate }
    }

    @Transactional
    fun updateStatus(id: UUID, status: InventoryStatus): Either<DomainError, InventoryItemDto> = transactionalEither {
        val item = findOrRaise(id)
        item.status = status
        item.updatedAt = OffsetDateTime.now()
        item.toDto(includeCost = true, toEur = exchangeRates::toEur)
    }

    @Transactional
    fun delete(id: UUID): Either<DomainError, Unit> = transactionalEither {
        ensure(items.existsById(id)) { CatalogError.InventoryItemNotFound }
        // Soft-deleting the item makes it invisible to @SQLRestriction, so any order item still
        // pointing at it would fail to load (EntityNotFoundException) unless detached first - the
        // real DB's ON DELETE SET NULL only fires on a hard delete, which this no longer is.
        orderItems.detachInventoryItem(id)
        items.deleteById(id)
        events.info("inventory_item_deleted itemId={}", id)
    }

    private fun Raise<DomainError>.applyImagesAndCategories(item: InventoryItem, request: InventoryItemRequest) {
        media.requireExisting(request.imageKeys).bind()
        request.imageKeys.forEachIndexed { index, key ->
            item.images.add(InventoryItemImage(inventoryItem = item, objectKey = key, sortOrder = index))
        }
        if (request.categoryIds.isNotEmpty()) {
            val found = categories.findAllById(request.categoryIds)
            ensure(found.size == request.categoryIds.toSet().size) { CatalogError.UnknownCategoryIds }
            item.categories.addAll(found)
        }
    }

    private fun Raise<DomainError>.applyTags(item: InventoryItem, request: InventoryItemRequest) {
        val tags = request.tags.map { it.trim() }.filter { it.isNotBlank() }
        ensure(tags.none { it.length > MAX_TAG_LENGTH }) { CatalogError.TagTooLong(MAX_TAG_LENGTH) }
        item.tags.clear()
        item.tags.addAll(tags)
    }

    // Items often share a sort key (or no sort is given at all); without a unique last key Postgres may
    // order the ties differently on each page, so paging repeats some items and skips others (#105).
    private fun withIdTiebreak(pageable: Pageable): Pageable =
        if (pageable.isUnpaged) pageable else PageRequest.of(pageable.pageNumber, pageable.pageSize, pageable.sort.and(Sort.by("id")))

    private fun Raise<DomainError>.findOrRaise(id: UUID): InventoryItem =
        ensureNotNull(items.findById(id).getOrNull()) { CatalogError.InventoryItemNotFound }
}
