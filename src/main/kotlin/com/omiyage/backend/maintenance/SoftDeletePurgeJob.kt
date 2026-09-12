package com.omiyage.backend.maintenance

import com.omiyage.backend.catalog.CategoryRepository
import com.omiyage.backend.catalog.InventoryItemImageRepository
import com.omiyage.backend.catalog.InventoryItemRepository
import com.omiyage.backend.order.OrderItemRepository
import com.omiyage.backend.order.OrderRepository
import com.omiyage.backend.shipment.ShipmentRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

private const val RETENTION_DAYS = 90L

@Component
class SoftDeletePurgeJob(
    private val orderItems: OrderItemRepository,
    private val orders: OrderRepository,
    private val shipments: ShipmentRepository,
    private val inventoryItems: InventoryItemRepository,
    private val inventoryItemImages: InventoryItemImageRepository,
    private val categories: CategoryRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    fun purgeSoftDeleted() {
        val cutoff = OffsetDateTime.now().minusDays(RETENTION_DAYS)
        // order_items before orders (FK), orders before shipments (FK); inventory_item_images
        // before inventory_items; categories is independent of the rest.
        val purgedItems = orderItems.purgeDeletedBefore(cutoff)
        val purgedOrders = orders.purgeDeletedBefore(cutoff)
        val purgedShipments = shipments.purgeDeletedBefore(cutoff)
        val purgedImages = inventoryItemImages.purgeDeletedBefore(cutoff)
        val purgedInventory = inventoryItems.purgeDeletedBefore(cutoff)
        val purgedCategories = categories.purgeDeletedBefore(cutoff)
        log.info(
            "soft-delete purge (older than {} days): {} order items, {} orders, {} shipments, {} inventory item images, {} inventory items, {} categories",
            RETENTION_DAYS,
            purgedItems,
            purgedOrders,
            purgedShipments,
            purgedImages,
            purgedInventory,
            purgedCategories,
        )
    }
}
