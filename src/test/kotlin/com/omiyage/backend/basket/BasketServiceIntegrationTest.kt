package com.omiyage.backend.basket

import com.omiyage.backend.errors.statusCode
import com.omiyage.backend.errors.failureOf
import com.omiyage.backend.errors.orFail
import com.omiyage.backend.PostgresIntegrationTest
import com.omiyage.backend.common.Currency
import com.omiyage.backend.order.OrderRepository
import com.omiyage.backend.order.OrderStatus
import com.omiyage.backend.security.MeishiPrincipal
import com.omiyage.backend.security.Role
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpStatus
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.util.UUID

@SpringBootTest
@Transactional
class BasketServiceIntegrationTest : PostgresIntegrationTest() {

    @Autowired
    lateinit var basketService: BasketService

    @Autowired
    lateinit var orderRepository: OrderRepository

    private fun user() = MeishiPrincipal(UUID.randomUUID(), "bob", Role.USER)

    private fun customLine(title: String = "Figure") =
        AddBasketLineRequest(customTitle = title, currency = Currency.JPY, maxPrice = BigDecimal("1500.00"), quantity = 2)

    @Test
    fun `added custom line is listed with its fields`() {
        val alice = user()

        val added = basketService.add(alice, customLine()).orFail()

        val listed = basketService.list(alice)
        assertEquals(listOf(added), listed)
        assertEquals("Figure", added.customTitle)
        assertEquals(2, added.quantity)
    }

    @Test
    fun `baskets are isolated between users`() {
        val alice = user()
        val bob = user()
        val line = basketService.add(alice, customLine()).orFail()

        assertTrue(basketService.list(bob).isEmpty())
        assertEquals(HttpStatus.NOT_FOUND, failureOf {
            basketService.update(bob, line.id, UpdateBasketLineRequest(customTitle = "Hijack"))
        }.statusCode)
        assertEquals(HttpStatus.NOT_FOUND, failureOf {
            basketService.remove(bob, line.id)
        }.statusCode)
        basketService.clear(bob)
        assertEquals(1, basketService.list(alice).size)
    }

    @Test
    fun `update changes quantity and price`() {
        val alice = user()
        val line = basketService.add(alice, customLine()).orFail()

        val updated = basketService.update(
            alice,
            line.id,
            UpdateBasketLineRequest(customTitle = "Figure", maxPrice = BigDecimal("900.00"), quantity = 5),
        ).orFail()

        assertEquals(5, updated.quantity)
        assertEquals(0, BigDecimal("900.00").compareTo(updated.maxPrice))
    }

    @Test
    fun `an update that omits fields keeps their current values`() {
        val alice = user()
        val line = basketService.add(alice, customLine().copy(customDescription = "Boxed")).orFail()

        val updated = basketService.update(alice, line.id, UpdateBasketLineRequest(maxPrice = BigDecimal("900.00"))).orFail()

        assertEquals("Figure", updated.customTitle)
        assertEquals("Boxed", updated.customDescription)
        assertEquals(2, updated.quantity)
        assertEquals(0, BigDecimal("900.00").compareTo(updated.maxPrice))

        val requantified = basketService.update(alice, line.id, UpdateBasketLineRequest(quantity = 4)).orFail()

        assertEquals(4, requantified.quantity)
        assertEquals(0, BigDecimal("900.00").compareTo(requantified.maxPrice))
    }

    @Test
    fun `a blank title is rejected on a custom line`() {
        val alice = user()
        val line = basketService.add(alice, customLine()).orFail()

        assertEquals(HttpStatus.BAD_REQUEST, failureOf {
            basketService.update(alice, line.id, UpdateBasketLineRequest(customTitle = " "))
        }.statusCode)
        assertEquals("Figure", basketService.list(alice).single().customTitle)
    }

    @Test
    fun `remove deletes one line and clear deletes all`() {
        val alice = user()
        val first = basketService.add(alice, customLine("A")).orFail()
        basketService.add(alice, customLine("B")).orFail()

        basketService.remove(alice, first.id).orFail()
        assertEquals(listOf("B"), basketService.list(alice).map { it.customTitle })

        basketService.clear(alice)
        assertTrue(basketService.list(alice).isEmpty())
    }

    @Test
    fun `line without inventory item or title is rejected`() {
        val error = failureOf {
            basketService.add(user(), AddBasketLineRequest(currency = Currency.JPY))
        }
        assertEquals(HttpStatus.BAD_REQUEST, error.statusCode)
    }

    @Test
    fun `unknown inventory item is rejected`() {
        val error = failureOf {
            basketService.add(user(), AddBasketLineRequest(inventoryItemId = UUID.randomUUID(), currency = Currency.JPY))
        }
        assertEquals(HttpStatus.NOT_FOUND, error.statusCode)
    }

    @Test
    fun `confirm creates an order from all lines and empties the basket`() {
        val alice = user()
        basketService.add(alice, customLine("A")).orFail()
        basketService.add(alice, customLine("B")).orFail()

        val order = basketService.confirm(alice).orFail()

        assertEquals(setOf("A", "B"), order.items.map { it.title }.toSet())
        assertEquals(alice.id, order.userId)
        assertTrue(basketService.list(alice).isEmpty())
    }

    @Test
    fun `confirm only takes the callers lines`() {
        val alice = user()
        val bob = user()
        basketService.add(alice, customLine("A")).orFail()
        basketService.add(bob, customLine("B")).orFail()

        basketService.confirm(alice).orFail()

        assertEquals(listOf("B"), basketService.list(bob).map { it.customTitle })
    }

    @Test
    fun `confirm on an empty basket is rejected`() {
        val error = failureOf { basketService.confirm(user()) }
        assertEquals(HttpStatus.CONFLICT, error.statusCode)
    }

    private fun orderWithStatus(owner: MeishiPrincipal, status: OrderStatus): UUID {
        basketService.add(owner, customLine("Existing")).orFail()
        val orderId = basketService.confirm(owner).orFail().id
        orderRepository.findById(orderId).get().status = status
        return orderId
    }

    @ParameterizedTest
    @EnumSource(OrderStatus::class, names = ["REQUESTED", "IN_PROGRESS", "PURCHASED"])
    fun `add to order moves the lines into an open order and empties the basket`(status: OrderStatus) {
        val alice = user()
        val orderId = orderWithStatus(alice, status)
        basketService.add(alice, customLine("A")).orFail()

        val order = basketService.addToOrder(alice, orderId).orFail()

        assertEquals(setOf("Existing", "A"), order.items.map { it.title }.toSet())
        assertTrue(basketService.list(alice).isEmpty())
    }

    @ParameterizedTest
    @EnumSource(OrderStatus::class, names = ["PREPARING_SHIPPING", "SENT", "DELIVERED", "CANCELLED"])
    fun `add to order is rejected for closed orders and keeps the basket`(status: OrderStatus) {
        val alice = user()
        val orderId = orderWithStatus(alice, status)
        basketService.add(alice, customLine("A")).orFail()

        val error = failureOf { basketService.addToOrder(alice, orderId) }

        assertEquals(HttpStatus.CONFLICT, error.statusCode)
        assertEquals(1, basketService.list(alice).size)
    }

    @Test
    fun `add to order hides another users order`() {
        val alice = user()
        val orderId = orderWithStatus(alice, OrderStatus.REQUESTED)
        val bob = user()
        basketService.add(bob, customLine("B")).orFail()

        val error = failureOf { basketService.addToOrder(bob, orderId) }

        assertEquals(HttpStatus.NOT_FOUND, error.statusCode)
    }

    @Test
    fun `add to order with an empty basket is rejected`() {
        val alice = user()
        val orderId = orderWithStatus(alice, OrderStatus.REQUESTED)

        val error = failureOf { basketService.addToOrder(alice, orderId) }

        assertEquals(HttpStatus.CONFLICT, error.statusCode)
    }
}
