package com.omiyage.backend.maintenance

import com.omiyage.backend.catalog.CategoryRepository
import com.omiyage.backend.catalog.InventoryItemImageRepository
import com.omiyage.backend.catalog.InventoryItemRepository
import com.omiyage.backend.combine.CombineRequestRepository
import com.omiyage.backend.media.MediaRepository
import com.omiyage.backend.order.OrderItemRepository
import com.omiyage.backend.order.OrderRepository
import com.omiyage.backend.shipment.ShipmentRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.OffsetDateTime

class SoftDeletePurgeJobTest {

    private val orderItems = mockk<OrderItemRepository>()
    private val orders = mockk<OrderRepository>()
    private val shipments = mockk<ShipmentRepository>()
    private val inventoryItems = mockk<InventoryItemRepository>()
    private val inventoryItemImages = mockk<InventoryItemImageRepository>()
    private val categories = mockk<CategoryRepository>()
    private val combineRequests = mockk<CombineRequestRepository>()
    private val media = mockk<MediaRepository>()

    private fun job(retentionDays: Long) = SoftDeletePurgeJob(
        orderItems, orders, shipments, inventoryItems, inventoryItemImages, categories, combineRequests, media, retentionDays,
    )

    @Test
    fun `rejects a retention below one day`() {
        assertThrows(IllegalArgumentException::class.java) { job(0) }
    }

    @Test
    fun `purges referencing rows before the rows they reference`() {
        val cutoff = mutableListOf<OffsetDateTime>()
        every { combineRequests.purgeReferencingPurgeableOrdersOrShipments(capture(cutoff)) } returns 1
        every { orderItems.purgeDeletedBefore(any()) } returns 2
        every { orders.purgeDeletedBefore(any()) } returns 3
        every { shipments.purgeDeletedBefore(any()) } returns 4
        every { inventoryItemImages.purgeDeletedBefore(any()) } returns 5
        every { inventoryItems.purgeDeletedBefore(any()) } returns 6
        every { categories.purgeDeletedBefore(any()) } returns 7
        every { media.purgeUnreferencedBefore(any()) } returns 8

        job(30).purgeSoftDeleted()

        verifyOrder {
            combineRequests.purgeReferencingPurgeableOrdersOrShipments(any())
            orderItems.purgeDeletedBefore(any())
            orders.purgeDeletedBefore(any())
            shipments.purgeDeletedBefore(any())
            inventoryItemImages.purgeDeletedBefore(any())
            inventoryItems.purgeDeletedBefore(any())
            media.purgeUnreferencedBefore(any())
        }
        verify(exactly = 1) { categories.purgeDeletedBefore(any()) }
        val expected = OffsetDateTime.now().minusDays(30)
        assertTrue(Duration.between(cutoff.single(), expected).abs() < Duration.ofMinutes(1))
    }
}
