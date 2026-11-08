package com.omiyage.backend.basket

import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import com.omiyage.backend.catalog.InventoryItemRepository
import com.omiyage.backend.common.Condition
import com.omiyage.backend.errors.BasketError
import com.omiyage.backend.errors.CatalogError
import com.omiyage.backend.errors.DomainError
import com.omiyage.backend.errors.transactionalEither
import com.omiyage.backend.order.CreateOrderItemRequest
import com.omiyage.backend.order.CreateOrderRequest
import com.omiyage.backend.order.OrderDto
import com.omiyage.backend.order.OrderService
import com.omiyage.backend.security.MeishiPrincipal
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

@Service
class BasketService(
    private val lines: BasketRepository,
    private val inventory: InventoryItemRepository,
    private val orders: OrderService,
) {

    @Transactional(readOnly = true)
    fun list(principal: MeishiPrincipal): List<BasketLineDto> =
        lines.findByUserIdOrderByCreatedAt(principal.id).map { it.toDto() }

    @Transactional
    fun add(principal: MeishiPrincipal, request: AddBasketLineRequest): Either<DomainError, BasketLineDto> = transactionalEither {
        val title = request.customTitle?.takeIf { it.isNotBlank() }
        ensure(request.inventoryItemId != null || title != null) { BasketError.LineNeedsItemOrTitle }
        ensure(request.inventoryItemId == null || inventory.existsById(request.inventoryItemId)) { CatalogError.InventoryItemNotFound }
        val line = BasketLine(
            userId = principal.id,
            inventoryItemId = request.inventoryItemId,
            customTitle = title,
            customDescription = request.customDescription,
            currency = requireNotNull(request.currency),
            maxPrice = request.maxPrice,
            quantity = request.quantity,
        )
        lines.save(line).toDto()
    }

    @Transactional
    fun update(principal: MeishiPrincipal, id: UUID, request: UpdateBasketLineRequest): Either<DomainError, BasketLineDto> = transactionalEither {
        val line = lineOrRaise(principal, id)
        request.customTitle?.let { title ->
            ensure(line.inventoryItemId != null || title.isNotBlank()) { BasketError.CustomLineNeedsTitle }
            line.customTitle = title.ifBlank { null }
        }
        request.customDescription?.let { line.customDescription = it }
        request.maxPrice?.let { line.maxPrice = it }
        request.quantity?.let { line.quantity = it }
        line.updatedAt = OffsetDateTime.now()
        line.toDto()
    }

    @Transactional
    fun remove(principal: MeishiPrincipal, id: UUID): Either<DomainError, Unit> = transactionalEither {
        lines.delete(lineOrRaise(principal, id))
    }

    @Transactional
    fun clear(principal: MeishiPrincipal) {
        lines.deleteByUserId(principal.id)
    }

    @Transactional
    fun confirm(principal: MeishiPrincipal): Either<DomainError, OrderDto> = transactionalEither {
        val basket = nonEmptyBasket(principal)
        val order = orders.create(principal, CreateOrderRequest(items = basket.map { it.toOrderItemRequest() })).bind()
        lines.deleteAll(basket)
        order
    }

    @Transactional
    fun addToOrder(principal: MeishiPrincipal, orderId: UUID): Either<DomainError, OrderDto> = transactionalEither {
        val basket = nonEmptyBasket(principal)
        val order = orders.addOwnItems(orderId, principal, basket.map { it.toOrderItemRequest() }).bind()
        lines.deleteAll(basket)
        order
    }

    private fun Raise<DomainError>.nonEmptyBasket(principal: MeishiPrincipal): List<BasketLine> {
        val basket = lines.findByUserIdOrderByCreatedAt(principal.id)
        ensure(basket.isNotEmpty()) { BasketError.BasketEmpty }
        return basket
    }

    private fun BasketLine.toOrderItemRequest() = CreateOrderItemRequest(
        inventoryItemId = inventoryItemId,
        title = customTitle,
        description = customDescription,
        condition = Condition.NEW.takeIf { inventoryItemId == null },
        currency = currency,
        maxPrice = maxPrice,
        quantity = quantity,
    )

    private fun Raise<DomainError>.lineOrRaise(principal: MeishiPrincipal, id: UUID): BasketLine =
        ensureNotNull(lines.findByIdAndUserId(id, principal.id)) { BasketError.LineNotFound }
}
