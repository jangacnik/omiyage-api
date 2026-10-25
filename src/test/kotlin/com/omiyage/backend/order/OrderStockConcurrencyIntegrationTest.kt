package com.omiyage.backend.order

import com.omiyage.backend.PostgresIntegrationTest
import com.omiyage.backend.catalog.InventoryItemRequest
import com.omiyage.backend.catalog.InventoryService
import com.omiyage.backend.common.Condition
import com.omiyage.backend.common.Currency
import com.omiyage.backend.security.MeishiPrincipal
import com.omiyage.backend.security.Role
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/** Not @Transactional: each thread needs its own real transaction for the lock to matter. */
@SpringBootTest
class OrderStockConcurrencyIntegrationTest : PostgresIntegrationTest() {

    @Autowired
    lateinit var orderService: OrderService

    @Autowired
    lateinit var inventoryService: InventoryService

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Test
    fun `two concurrent orders for the last unit reserve it exactly once`() {
        val item = inventoryService.create(
            InventoryItemRequest(
                title = "Last unit",
                description = null,
                condition = Condition.NEW,
                priceAmount = BigDecimal("500"),
                currency = Currency.JPY,
                stockQuantity = 1,
            ),
        )
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val attempts = List(2) {
            pool.submit {
                start.await()
                runCatching {
                    orderService.create(
                        MeishiPrincipal(UUID.randomUUID(), "bob", Role.USER),
                        CreateOrderRequest(items = listOf(CreateOrderItemRequest(inventoryItemId = item.id, currency = Currency.JPY))),
                    )
                }
            }
        }
        start.countDown()
        val results = attempts.map { it.get() }
        pool.shutdown()

        assertEquals(0, inventoryService.getById(item.id).stockQuantity)
        assertTrue(results.any { it.isSuccess }, "at least one order must win the last unit")
        val reservedUnits = jdbc.queryForObject(
            "SELECT COALESCE(SUM(reserved_quantity), 0) FROM order_items WHERE inventory_item_id = ?",
            Long::class.java,
            item.id,
        )
        assertEquals(1L, reservedUnits, "the last unit must be reserved exactly once")
    }
}
