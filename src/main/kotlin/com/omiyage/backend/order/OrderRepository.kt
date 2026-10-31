package com.omiyage.backend.order

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.OffsetDateTime
import java.util.UUID

interface OrderRepository : JpaRepository<Order, UUID> {
    fun findByUserId(userId: UUID, pageable: Pageable): Page<Order>
    fun findByUserIdIn(userIds: Collection<UUID>): List<Order>
    fun findByUserIdAndCreatedAtAfter(userId: UUID, createdAt: OffsetDateTime): List<Order>

    // Serialises order creation per user for the rest of the transaction, so two simultaneous
    // identical submits cannot both pass the duplicate check. CAST because void has no JDBC mapping.
    @Query(value = "SELECT CAST(pg_advisory_xact_lock(hashtext(:key)) AS text)", nativeQuery = true)
    fun lockOrderCreation(@Param("key") key: String): String?

    @Query(
        value = "SELECT o FROM Order o WHERE (:status IS NULL OR o.status = :status) AND o.status NOT IN :excluded",
        countQuery = "SELECT COUNT(o) FROM Order o WHERE (:status IS NULL OR o.status = :status) AND o.status NOT IN :excluded",
    )
    fun findFiltered(
        @Param("status") status: OrderStatus?,
        @Param("excluded") excluded: Collection<OrderStatus>,
        pageable: Pageable,
    ): Page<Order>

    fun existsByShipment_Id(shipmentId: UUID): Boolean

    // "Order by item count" isn't a mapped property, so it can't ride Pageable's Sort like
    // updatedAt/createdAt can - two explicit directions instead. LEFT JOIN so a zero-item order
    // (shouldn't normally happen, but exists transiently) still sorts as count 0 rather than being
    // dropped by the join.
    @Query(
        value = "SELECT o FROM Order o LEFT JOIN o.items i WHERE (:status IS NULL OR o.status = :status) AND o.status NOT IN :excluded GROUP BY o ORDER BY COUNT(i) ASC, o.createdAt DESC, o.id",
        countQuery = "SELECT COUNT(o) FROM Order o WHERE (:status IS NULL OR o.status = :status) AND o.status NOT IN :excluded",
    )
    fun findAllOrderByItemCountAsc(
        @Param("status") status: OrderStatus?,
        @Param("excluded") excluded: Collection<OrderStatus>,
        pageable: Pageable,
    ): Page<Order>

    @Query(
        value = "SELECT o FROM Order o LEFT JOIN o.items i WHERE (:status IS NULL OR o.status = :status) AND o.status NOT IN :excluded GROUP BY o ORDER BY COUNT(i) DESC, o.createdAt DESC, o.id",
        countQuery = "SELECT COUNT(o) FROM Order o WHERE (:status IS NULL OR o.status = :status) AND o.status NOT IN :excluded",
    )
    fun findAllOrderByItemCountDesc(
        @Param("status") status: OrderStatus?,
        @Param("excluded") excluded: Collection<OrderStatus>,
        pageable: Pageable,
    ): Page<Order>

    @Modifying
    @Query(value = "DELETE FROM orders WHERE deleted_at IS NOT NULL AND deleted_at < :cutoff", nativeQuery = true)
    fun purgeDeletedBefore(cutoff: OffsetDateTime): Int

    // shipment_id has no ON DELETE clause (RESTRICT) and no check constraint tying it to another
    // column, so a plain null-out is safe here - unlike detachInventoryItem, no data to snapshot.
    // clearAutomatically: a bulk update doesn't refresh already-loaded Order instances in this
    // persistence context, so a caller reading one of these orders again in the same transaction
    // would otherwise see the stale (pre-detach) shipment reference.
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Order o SET o.shipment = null, o.version = o.version + 1 WHERE o.shipment.id = :shipmentId")
    fun detachShipment(@Param("shipmentId") shipmentId: UUID): Int
}

interface OrderItemRepository : JpaRepository<OrderItem, UUID> {
    @Modifying
    @Query(value = "DELETE FROM order_items WHERE deleted_at IS NOT NULL AND deleted_at < :cutoff", nativeQuery = true)
    fun purgeDeletedBefore(cutoff: OffsetDateTime): Int

    // Plain "set inventory_item_id = null" would violate order_items_source_check whenever the
    // item was created purely by reference (no custom_title) - snapshot the catalog data being
    // detached so the row stays valid and the order still shows what was actually ordered.
    // clearAutomatically: see detachShipment above - same stale-persistence-context risk.
    @Modifying(clearAutomatically = true)
    @Query(
        value = """
            UPDATE order_items oi
            SET inventory_item_id = NULL,
                custom_title = COALESCE(oi.custom_title, ii.title),
                custom_description = COALESCE(oi.custom_description, ii.description),
                condition = COALESCE(oi.condition, ii.condition),
                custom_image_key = COALESCE(oi.custom_image_key, (
                    SELECT img.object_key FROM inventory_item_images img
                    WHERE img.inventory_item_id = ii.id AND img.deleted_at IS NULL
                    ORDER BY img.sort_order ASC LIMIT 1
                ))
            FROM inventory_items ii
            WHERE oi.inventory_item_id = ii.id AND oi.inventory_item_id = :inventoryItemId
        """,
        nativeQuery = true,
    )
    fun detachInventoryItem(@Param("inventoryItemId") inventoryItemId: UUID): Int
}
