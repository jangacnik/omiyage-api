package com.omiyage.backend.shipment

import com.omiyage.backend.common.Currency
import com.omiyage.backend.dashboard.PendingAmountDto
import com.omiyage.backend.order.OrderDto
import com.omiyage.backend.order.OrderStatus
import com.omiyage.backend.order.totalWeightGrams
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

fun Shipment.toSummaryDto() = ShipmentSummaryDto(
    id = requireNotNull(id),
    title = title,
    itemCount = billableOrders().sumOf { it.items.size },
    totalWeightGrams = billableOrders().fold(BigDecimal.ZERO) { sum, order -> sum + order.totalWeightGrams() },
)

fun Shipment.toBrowsableDto(ownerNickname: String?) = BrowsableShipmentDto(
    id = requireNotNull(id),
    title = title,
    itemCount = billableOrders().sumOf { it.items.size },
    totalWeightGrams = billableOrders().fold(BigDecimal.ZERO) { sum, order -> sum + order.totalWeightGrams() },
    ownerNickname = ownerNickname,
)

// A cancelled order contributes nothing to ship - same convention as ShipmentDto.toDto's weight
// calculation, just without needing the shipping-rate/exchange-rate pipeline that full DTO pulls in.
private fun Shipment.billableOrders() = orders.filter { it.status != OrderStatus.CANCELLED }

/** Minimal shipment info safe to show for combine-request purposes: no order/member detail. */
data class ShipmentSummaryDto(
    val id: UUID,
    val title: String?,
    val itemCount: Int,
    val totalWeightGrams: BigDecimal,
)

/** Same shape as ShipmentSummaryDto plus the owner's nickname - used only for the "browse other
 *  members' shipments to combine into" listing, where knowing whose shipment it is matters. */
data class BrowsableShipmentDto(
    val id: UUID,
    val title: String?,
    val itemCount: Int,
    val totalWeightGrams: BigDecimal,
    val ownerNickname: String?,
)

data class ShipmentMemberDto(
    val userId: UUID,
    val orders: List<OrderDto>,
    // Sum of each item's actualPrice (JPY, converted) or maxPrice (EUR) times its quantity,
    // normalized to EUR - same convention as DashboardService.pendingAmounts, just scoped to this
    // member's own orders here. A list for response-shape stability, but at most one entry.
    val itemTotals: List<PendingAmountDto>,
    val shippingShareJpy: Int?,
    val shippingShareEur: BigDecimal?,
)

data class MemberShipmentDto(
    val id: UUID,
    val title: String?,
    val status: ShipmentStatus,
    val destination: ShipmentDestination?,
    val shippingMethod: ShippingMethod?,
    val trackingNumber: String?,
    val ownerUserId: UUID?,
    val totalWeightGrams: BigDecimal,
    val shippingCostJpy: Int?,
    val shippingCostEur: BigDecimal?,
    val estimatedDays: Int?,
    val shippedAt: OffsetDateTime?,
    val arrivedAt: OffsetDateTime?,
    val members: List<ShipmentMemberDto>,
    val grandTotalItemTotals: List<PendingAmountDto>,
)

// Shipment.toDto() always computes orders with includeProfit=true (every admin shipment endpoint
// is admin-only) - strip profit/soldPrice/actualPrice back out here, since this DTO is served to
// members. `toEur` is the bare admin-set-rate conversion (ExchangeRateService.toEur) for turning
// a JPY actualPrice into the EUR everything else here is already denominated in.
fun ShipmentDto.toMemberDto(toEur: (BigDecimal) -> BigDecimal): MemberShipmentDto {
    val byUser = orders.groupBy { it.userId }
    val members = byUser.map { (userId, memberOrders) ->
        val billable = memberOrders.filter { it.status != OrderStatus.CANCELLED }
        // Computed from the still-unstripped orders below (memberOrders), before actualPrice gets
        // nulled out for the response - explicit scale: toEur() returns scale-2, but maxPrice may
        // not be, and BigDecimal arithmetic takes the larger of the two scales it's mixing.
        val itemTotal = billable.flatMap { it.items }.sumOf { item ->
            val amount = item.actualPrice?.let(toEur) ?: item.maxPrice ?: BigDecimal.ZERO
            amount * BigDecimal(item.quantity)
        }.setScale(2, java.math.RoundingMode.HALF_UP)
        val strippedOrders = memberOrders.map { order ->
            order.copy(
                profit = null,
                items = order.items.map { it.copy(soldPrice = null, profit = null, actualPrice = null, actualPriceCurrency = null) },
            )
        }
        ShipmentMemberDto(
            userId = userId,
            orders = strippedOrders,
            itemTotals = if (itemTotal > BigDecimal.ZERO) listOf(PendingAmountDto(Currency.EUR, itemTotal)) else emptyList(),
            shippingShareJpy = billable.sumOf { it.shippingCostShareJpy ?: 0 }.takeIf { billable.any { o -> o.shippingCostShareJpy != null } },
            shippingShareEur = billable.mapNotNull { it.shippingCostShareEur }.takeIf { it.isNotEmpty() }?.fold(BigDecimal.ZERO, BigDecimal::add),
        )
    }.sortedBy { it.userId }

    val grandTotal = members.flatMap { it.itemTotals }.fold(BigDecimal.ZERO) { sum, dto -> sum + dto.amount }
    val grandTotals = if (grandTotal > BigDecimal.ZERO) listOf(PendingAmountDto(Currency.EUR, grandTotal)) else emptyList()

    return MemberShipmentDto(
        id = id,
        title = title,
        status = status,
        destination = destination,
        shippingMethod = shippingMethod,
        trackingNumber = trackingNumber,
        ownerUserId = ownerUserId,
        totalWeightGrams = totalWeightGrams,
        shippingCostJpy = shippingCostJpy,
        shippingCostEur = shippingCostEur,
        estimatedDays = estimatedDays,
        shippedAt = shippedAt,
        arrivedAt = arrivedAt,
        members = members,
        grandTotalItemTotals = grandTotals,
    )
}
