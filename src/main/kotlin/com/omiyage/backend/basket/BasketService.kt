package com.omiyage.backend.basket

import com.omiyage.backend.catalog.InventoryItemRepository
import com.omiyage.backend.order.CreateOrderItemRequest
import com.omiyage.backend.order.CreateOrderRequest
import com.omiyage.backend.order.OrderDto
import com.omiyage.backend.order.OrderService
import com.omiyage.backend.security.MeishiPrincipal
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
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
    fun add(principal: MeishiPrincipal, request: AddBasketLineRequest): BasketLineDto {
        val title = request.customTitle?.takeIf { it.isNotBlank() }
        if (request.inventoryItemId == null && title == null) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "a basket line needs an inventory item or a custom title")
        }
        if (request.inventoryItemId != null && !inventory.existsById(request.inventoryItemId)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "inventory item not found")
        }
        val line = BasketLine(
            userId = principal.id,
            inventoryItemId = request.inventoryItemId,
            customTitle = title,
            customDescription = request.customDescription,
            currency = requireNotNull(request.currency),
            maxPrice = request.maxPrice,
            quantity = request.quantity,
        )
        return lines.save(line).toDto()
    }

    @Transactional
    fun update(principal: MeishiPrincipal, id: UUID, request: UpdateBasketLineRequest): BasketLineDto {
        val line = lineOrThrow(principal, id)
        val title = request.customTitle?.takeIf { it.isNotBlank() }
        if (line.inventoryItemId == null && title == null) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "a custom line needs a title")
        }
        line.customTitle = title
        line.customDescription = request.customDescription
        line.maxPrice = request.maxPrice
        line.quantity = request.quantity
        line.updatedAt = OffsetDateTime.now()
        return line.toDto()
    }

    @Transactional
    fun remove(principal: MeishiPrincipal, id: UUID) = lines.delete(lineOrThrow(principal, id))

    @Transactional
    fun clear(principal: MeishiPrincipal) {
        lines.deleteByUserId(principal.id)
    }

    @Transactional
    fun confirm(principal: MeishiPrincipal): OrderDto {
        val basket = nonEmptyBasket(principal)
        val order = orders.create(principal, CreateOrderRequest(items = basket.map { it.toOrderItemRequest() }))
        lines.deleteAll(basket)
        return order
    }

    private fun nonEmptyBasket(principal: MeishiPrincipal): List<BasketLine> =
        lines.findByUserIdOrderByCreatedAt(principal.id).ifEmpty {
            throw ResponseStatusException(HttpStatus.CONFLICT, "basket is empty")
        }

    private fun BasketLine.toOrderItemRequest() = CreateOrderItemRequest(
        inventoryItemId = inventoryItemId,
        title = customTitle,
        description = customDescription,
        currency = currency,
        maxPrice = maxPrice,
        quantity = quantity,
    )

    private fun lineOrThrow(principal: MeishiPrincipal, id: UUID): BasketLine =
        lines.findByIdAndUserId(id, principal.id)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "basket line not found")
}
