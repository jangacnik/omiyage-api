package com.omiyage.backend.basket

import com.omiyage.backend.PostgresIntegrationTest
import com.omiyage.backend.common.Currency
import com.omiyage.backend.security.MeishiPrincipal
import com.omiyage.backend.security.Role
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
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

    private fun user() = MeishiPrincipal(UUID.randomUUID(), "bob", Role.USER)

    private fun customLine(title: String = "Figure") =
        AddBasketLineRequest(customTitle = title, currency = Currency.JPY, maxPrice = BigDecimal("1500.00"), quantity = 2)

    @Test
    fun `added custom line is listed with its fields`() {
        val alice = user()

        val added = basketService.add(alice, customLine())

        val listed = basketService.list(alice)
        assertEquals(listOf(added), listed)
        assertEquals("Figure", added.customTitle)
        assertEquals(2, added.quantity)
    }

    @Test
    fun `baskets are isolated between users`() {
        val alice = user()
        val bob = user()
        val line = basketService.add(alice, customLine())

        assertTrue(basketService.list(bob).isEmpty())
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException::class.java) {
            basketService.update(bob, line.id, UpdateBasketLineRequest(customTitle = "Hijack"))
        }.statusCode)
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ResponseStatusException::class.java) {
            basketService.remove(bob, line.id)
        }.statusCode)
        basketService.clear(bob)
        assertEquals(1, basketService.list(alice).size)
    }

    @Test
    fun `update changes quantity and price`() {
        val alice = user()
        val line = basketService.add(alice, customLine())

        val updated = basketService.update(
            alice,
            line.id,
            UpdateBasketLineRequest(customTitle = "Figure", maxPrice = BigDecimal("900.00"), quantity = 5),
        )

        assertEquals(5, updated.quantity)
        assertEquals(0, BigDecimal("900.00").compareTo(updated.maxPrice))
    }

    @Test
    fun `remove deletes one line and clear deletes all`() {
        val alice = user()
        val first = basketService.add(alice, customLine("A"))
        basketService.add(alice, customLine("B"))

        basketService.remove(alice, first.id)
        assertEquals(listOf("B"), basketService.list(alice).map { it.customTitle })

        basketService.clear(alice)
        assertTrue(basketService.list(alice).isEmpty())
    }

    @Test
    fun `line without inventory item or title is rejected`() {
        val error = assertThrows(ResponseStatusException::class.java) {
            basketService.add(user(), AddBasketLineRequest(currency = Currency.JPY))
        }
        assertEquals(HttpStatus.BAD_REQUEST, error.statusCode)
    }

    @Test
    fun `unknown inventory item is rejected`() {
        val error = assertThrows(ResponseStatusException::class.java) {
            basketService.add(user(), AddBasketLineRequest(inventoryItemId = UUID.randomUUID(), currency = Currency.JPY))
        }
        assertEquals(HttpStatus.NOT_FOUND, error.statusCode)
    }
}
