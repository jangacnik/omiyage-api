package com.omiyage.backend.order

import com.omiyage.backend.errors.orFail
import com.omiyage.backend.PostgresIntegrationTest
import com.omiyage.backend.catalog.InventoryItemRequest
import com.omiyage.backend.catalog.InventoryService
import com.omiyage.backend.common.Condition
import com.omiyage.backend.common.Currency
import com.omiyage.backend.security.MeishiPrincipal
import com.omiyage.backend.security.Role
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import java.math.BigDecimal
import java.util.Random
import java.util.UUID

private const val CASES = 10
private const val STEPS = 25

/**
 * Model-based check of stock reservation and item moves (#76 target 5). Random action sequences run
 * against the real OrderService, each call in its own transaction, and the invariants are checked
 * in the database after every step. jqwik has no Spring integration here, so cases come from a
 * seeded Random and the seed is in every failure message. Not @Transactional: a rejected call must
 * roll back on its own, exactly as in production.
 */
@SpringBootTest
class OrderStockConservationIntegrationTest : PostgresIntegrationTest() {

    @Autowired
    lateinit var orderService: OrderService

    @Autowired
    lateinit var inventoryService: InventoryService

    @Autowired
    lateinit var jdbc: JdbcTemplate

    private val allUsers = mutableListOf<MeishiPrincipal>()
    private val inventoryIds = mutableListOf<UUID>()

    @AfterEach
    fun deleteCommittedRows() {
        allUsers.forEach { user ->
            jdbc.update("DELETE FROM order_items WHERE order_id IN (SELECT id FROM orders WHERE user_id = ?)", user.id)
            jdbc.update("DELETE FROM orders WHERE user_id = ?", user.id)
        }
        inventoryIds.forEach { jdbc.update("DELETE FROM inventory_items WHERE id = ?", it) }
    }

    private class Run(val random: Random, val seed: Long, val initialStock: Map<UUID, Int>, val users: List<MeishiPrincipal>) {
        val expectedItems = mutableMapOf<UUID, MutableSet<UUID>>()
        var step = 0
        fun context() = "seed=$seed step=$step"
    }

    @Test
    fun `stock is conserved and items are never lost, duplicated or moved across users`() {
        repeat(CASES) { case ->
            val seed = System.nanoTime() + case
            val random = Random(seed)
            val initialStock = List(2) {
                val stock = random.nextInt(6)
                val item = inventoryService.create(
                    InventoryItemRequest(
                        title = "Stock ${UUID.randomUUID()}",
                        description = null,
                        condition = Condition.NEW,
                        priceAmount = BigDecimal("5.00"),
                        currency = Currency.EUR,
                        stockQuantity = stock,
                    ),
                ).orFail()
                inventoryIds.add(item.id)
                item.id to stock
            }.toMap()
            val users = List(2) { MeishiPrincipal(UUID.randomUUID(), "member$it", Role.USER) }
            allUsers += users
            val run = Run(random, seed, initialStock, users)
            users.forEach { run.expectedItems[it.id] = mutableSetOf() }
            repeat(STEPS) {
                run.step = it
                act(run)
                checkInvariants(run)
            }
        }
    }

    private fun catalogLine(run: Run) = CreateOrderItemRequest(
        inventoryItemId = run.initialStock.keys.elementAt(run.random.nextInt(run.initialStock.size)),
        currency = Currency.EUR,
        quantity = 1 + run.random.nextInt(4),
    )

    private fun <T> List<T>.pick(random: Random): T? = if (isEmpty()) null else this[random.nextInt(size)]

    private fun openOrders(userId: UUID): List<OrderDto> = orderService.adminOrdersForUser(userId).filter { it.open }

    private fun act(run: Run) {
        val random = run.random
        val users = run.users
        val user = users[random.nextInt(users.size)]
        val expected = run.expectedItems.getValue(user.id)
        val open = openOrders(user.id)
        val order = open.pick(random)
        when (random.nextInt(8)) {
            0 -> {
                val lines = List(1 + random.nextInt(2)) { catalogLine(run) }
                orderService.create(user, CreateOrderRequest(items = lines, nickname = UUID.randomUUID().toString()))
                .onRight { created -> expected += created.items.map { it.id } }
            }
            1 -> if (order != null) {
                orderService.addOwnItem(order.id, user, catalogLine(run)).onRight { updated -> expected += updated.items.map { it.id } }
            }
            2 -> order?.items?.pick(random)?.let { item ->
                orderService.updateOwnItem(order.id, item.id, user, UpdateOwnOrderItemRequest(quantity = 1 + random.nextInt(6)))
            }
            3 -> order?.items?.pick(random)?.let { item ->
                orderService.removeOwnItem(order.id, item.id, user).onRight { expected -= item.id }
            }
            4 -> if (order != null) orderService.cancelOwn(order.id, user)
            5 -> if (open.size >= 2) orderService.mergeMine(user, open.shuffled(random).take(2).map { it.id })
            6 -> if (order != null && order.items.size >= 2) {
                orderService.splitMine(user, order.id, order.items.shuffled(random).take(1 + random.nextInt(order.items.size - 1)).map { it.id })
            }
            else -> if (order != null && order.items.size >= 2) {
                val target = (open - order).pick(random)
                orderService.adminMoveItems(order.id, order.items.shuffled(random).take(1 + random.nextInt(order.items.size - 1)).map { it.id }, target?.id)
            }
        }
    }

    private fun checkInvariants(run: Run) {
        val users = run.users
        run.initialStock.forEach { (inventoryId, initial) ->
            val stock = jdbc.queryForObject("SELECT stock_quantity FROM inventory_items WHERE id = ?", Int::class.java, inventoryId)!!
            val reserved = jdbc.queryForObject(
                "SELECT COALESCE(SUM(i.reserved_quantity), 0) FROM order_items i JOIN orders o ON o.id = i.order_id " +
                    "WHERE i.inventory_item_id = ? AND i.deleted_at IS NULL AND o.deleted_at IS NULL",
                Int::class.java,
                inventoryId,
            )!!
            assertThat(stock).`as`("stock never negative, ${run.context()}").isGreaterThanOrEqualTo(0)
            assertThat(stock + reserved).`as`("stock + reserved == initial, ${run.context()}").isEqualTo(initial)
        }
        val overReserved = jdbc.queryForObject(
            "SELECT COUNT(*) FROM order_items i JOIN orders o ON o.id = i.order_id " +
                "WHERE o.user_id IN (?, ?) AND i.deleted_at IS NULL AND i.reserved_quantity > i.quantity",
            Int::class.java,
            users[0].id,
            users[1].id,
        )
        assertThat(overReserved).`as`("reserved <= quantity, ${run.context()}").isZero()
        users.forEach { user ->
            val actual = jdbc.queryForList(
                "SELECT i.id FROM order_items i JOIN orders o ON o.id = i.order_id " +
                    "WHERE o.user_id = ? AND i.deleted_at IS NULL AND o.deleted_at IS NULL",
                UUID::class.java,
                user.id,
            )
            assertThat(actual).`as`("each item exactly once, on its owner's orders, ${run.context()}")
                .containsExactlyInAnyOrderElementsOf(run.expectedItems.getValue(user.id))
        }
        val badCancelled = jdbc.queryForObject(
            "SELECT COUNT(*) FROM orders o WHERE o.user_id IN (?, ?) AND o.deleted_at IS NULL AND o.status = 'CANCELLED' AND (o.shipment_id IS NOT NULL " +
                "OR EXISTS (SELECT 1 FROM order_items i WHERE i.order_id = o.id AND i.deleted_at IS NULL AND i.reserved_quantity > 0))",
            Int::class.java,
            users[0].id,
            users[1].id,
        )
        assertThat(badCancelled).`as`("cancelled orders hold no reservation and no shipment, ${run.context()}").isZero()
    }
}
