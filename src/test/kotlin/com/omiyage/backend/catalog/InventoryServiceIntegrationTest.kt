package com.omiyage.backend.catalog

import com.omiyage.backend.PostgresIntegrationTest
import com.omiyage.backend.common.Condition
import com.omiyage.backend.common.Currency
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.domain.PageRequest
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

@SpringBootTest
@Transactional
class InventoryServiceIntegrationTest : PostgresIntegrationTest() {

    @Autowired
    lateinit var inventoryService: InventoryService

    @Autowired
    lateinit var categoryService: CategoryService

    private fun request(title: String, categoryIds: List<java.util.UUID> = emptyList()) = InventoryItemRequest(
        title = title,
        description = null,
        condition = Condition.NEW,
        priceAmount = BigDecimal("1000.00"),
        currency = Currency.JPY,
        stockQuantity = 1,
        categoryIds = categoryIds,
    )

    @Test
    fun `search filters by title case-insensitively`() {
        inventoryService.create(request("Kit Kat matcha"))
        inventoryService.create(request("Pocky strawberry"))

        val results = inventoryService.search("KIT KAT", null, InventoryStatus.ACTIVE, PageRequest.of(0, 20))

        assertEquals(1, results.content.size)
        assertEquals("Kit Kat matcha", results.content[0].title)
    }

    @Test
    fun `search filters by category`() {
        val snacks = categoryService.create("Snacks", "snacks-${System.nanoTime()}")
        val toys = categoryService.create("Toys", "toys-${System.nanoTime()}")
        inventoryService.create(request("Kit Kat matcha", listOf(snacks.id)))
        inventoryService.create(request("Gundam model kit", listOf(toys.id)))

        val results = inventoryService.search(null, snacks.id, InventoryStatus.ACTIVE, PageRequest.of(0, 20))

        assertEquals(1, results.content.size)
        assertEquals("Kit Kat matcha", results.content[0].title)
    }

    @Test
    fun `search only returns ACTIVE items when status filter is ACTIVE`() {
        val created = inventoryService.create(request("Discontinued snack"))
        inventoryService.updateStatus(created.id, InventoryStatus.ARCHIVED)

        val active = inventoryService.search(null, null, InventoryStatus.ACTIVE, PageRequest.of(0, 20))
        val all = inventoryService.search(null, null, null, PageRequest.of(0, 20))

        assertTrue(active.content.none { it.id == created.id })
        assertTrue(all.content.any { it.id == created.id })
    }

    @Test
    fun `search paginates results`() {
        repeat(5) { inventoryService.create(request("Item $it")) }

        val firstPage = inventoryService.search(null, null, InventoryStatus.ACTIVE, PageRequest.of(0, 2))
        val secondPage = inventoryService.search(null, null, InventoryStatus.ACTIVE, PageRequest.of(1, 2))

        assertEquals(2, firstPage.content.size)
        assertEquals(2, secondPage.content.size)
        assertEquals(3, firstPage.totalPages)
        assertEquals(5, firstPage.totalElements)
    }
}
