package com.omiyage.backend.maintenance

import com.omiyage.backend.catalog.CategoryRepository
import com.omiyage.backend.catalog.InventoryItemImageRepository
import com.omiyage.backend.catalog.InventoryItemRepository
import com.omiyage.backend.combine.CombineRequestRepository
import com.omiyage.backend.media.MediaRepository
import com.omiyage.backend.order.OrderItemRepository
import com.omiyage.backend.order.OrderRepository
import com.omiyage.backend.shipment.ShipmentRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime

private const val UNATTACHED_MEDIA_GRACE_DAYS = 1L

// Runs on every replica with no distributed lock; deletes are idempotent, but keep the deployment at
// one replica (or add ShedLock) if the nightly run ever needs to be exactly-once.
@Component
class SoftDeletePurgeJob(
    private val orderItems: OrderItemRepository,
    private val orders: OrderRepository,
    private val shipments: ShipmentRepository,
    private val inventoryItems: InventoryItemRepository,
    private val inventoryItemImages: InventoryItemImageRepository,
    private val categories: CategoryRepository,
    private val combineRequests: CombineRequestRepository,
    private val media: MediaRepository,
    @Value("\${omiyage.maintenance.soft-delete-retention-days}") private val retentionDays: Long,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    init {
        require(retentionDays >= 1) { "soft-delete retention must be at least 1 day" }
    }

    @Scheduled(cron = "0 0 3 * * *")
    @Transactional
    fun purgeSoftDeleted() {
        val cutoff = OffsetDateTime.now().minusDays(retentionDays)
        // combine_requests before orders/shipments (FK, both NOT NULL with no ON DELETE - a
        // leftover row referencing a purgeable order/shipment would otherwise FK-violate and
        // roll back this entire transaction, every night, forever); order_items before orders
        // (FK), orders before shipments (FK); inventory_item_images before inventory_items;
        // categories is independent of the rest.
        val purgedCombineRequests = combineRequests.purgeReferencingPurgeableOrdersOrShipments(cutoff)
        val purgedItems = orderItems.purgeDeletedBefore(cutoff)
        val purgedOrders = orders.purgeDeletedBefore(cutoff)
        val purgedShipments = shipments.purgeDeletedBefore(cutoff)
        val purgedImages = inventoryItemImages.purgeDeletedBefore(cutoff)
        val purgedInventory = inventoryItems.purgeDeletedBefore(cutoff)
        val purgedCategories = categories.purgeDeletedBefore(cutoff)
        // after image/order-item purges so their rows no longer count as references; the grace
        // period keeps freshly uploaded files that are not attached to anything yet.
        val purgedMedia = media.purgeUnreferencedBefore(OffsetDateTime.now().minusDays(UNATTACHED_MEDIA_GRACE_DAYS))
        log.info(
            "soft-delete purge (older than {} days): {} combine requests, {} order items, {} orders, {} shipments, {} inventory item images, {} inventory items, {} categories, {} media",
            retentionDays,
            purgedCombineRequests,
            purgedItems,
            purgedOrders,
            purgedShipments,
            purgedImages,
            purgedInventory,
            purgedCategories,
            purgedMedia,
        )
    }
}
