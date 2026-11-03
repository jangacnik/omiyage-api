package com.omiyage.backend.shipment

import com.omiyage.backend.common.Currency
import com.omiyage.backend.order.Order
import com.omiyage.backend.order.OrderItem
import com.omiyage.backend.order.OrderStatus
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.Combinators
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.assertj.core.api.Assertions.assertThat
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Random
import java.util.UUID

class ShipmentCostAllocationProperties {

    data class ItemSpec(val weight: BigDecimal, val quantity: Int)

    data class OrderSpec(val items: List<ItemSpec>, val cancelled: Boolean)

    data class ShipmentSpec(val orders: List<OrderSpec>, val costJpy: Int, val jpyPerEur: BigDecimal) {
        val zeroWeight: ShipmentSpec
            get() = copy(orders = orders.map { order -> order.copy(items = order.items.map { it.copy(weight = BigDecimal.ZERO) }) })

        fun toEurWithMarkup(jpyAmount: Int): BigDecimal =
            BigDecimal(jpyAmount).divide(jpyPerEur, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal("1.15"))
                .setScale(2, RoundingMode.HALF_UP)
    }

    private fun OrderSpec.toOrder(index: Int): Order {
        val order = Order(id = UUID(0, index.toLong()), userId = UUID(1, 0))
        if (cancelled) order.status = OrderStatus.CANCELLED
        items.forEachIndexed { itemIndex, item ->
            order.items.add(
                OrderItem(
                    id = UUID(index.toLong(), itemIndex.toLong()),
                    order = order,
                    customTitle = "item",
                    currency = Currency.JPY,
                    quantity = item.quantity,
                    weight = item.weight,
                ),
            )
        }
        return order
    }

    private fun ShipmentSpec.orderEntities() = orders.mapIndexed { index, order -> order.toOrder(index) }

    private fun ShipmentSpec.dtoOf(orderEntities: List<Order>): ShipmentDto {
        val shipment = Shipment(id = UUID(2, 0), destination = ShipmentDestination.AUSTRIA, shippingMethod = ShippingMethod.EMS)
        shipment.orders.addAll(orderEntities)
        return shipment.toDto({ _, _, _ -> ShippingQuote(costJpy, 7) }, { toEurWithMarkup(it) }, { it })
    }

    private fun ShipmentSpec.dto() = dtoOf(orderEntities())

    private fun ShipmentDto.billableOrders() = orders.filter { it.status != OrderStatus.CANCELLED }

    private fun ShipmentDto.sharesById() = orders.associate { it.id to (it.shippingCostShareJpy to it.shippingCostShareEur) }

    @Provide
    fun shipments(): Arbitrary<ShipmentSpec> {
        val weights = Arbitraries.integers().between(0, 3)
            .flatMap { scale -> Arbitraries.bigDecimals().between(BigDecimal.ZERO, BigDecimal(5000)).ofScale(scale) }
        val items = Combinators.combine(weights, Arbitraries.integers().between(1, 1000)).`as` { weight, quantity -> ItemSpec(weight, quantity) }
        val orders = Combinators.combine(items.list().ofMaxSize(6), Arbitraries.integers().between(0, 3).map { it == 0 })
            .`as` { orderItems, cancelled -> OrderSpec(orderItems, cancelled) }
        return Combinators.combine(
            orders.list().ofMinSize(1).ofMaxSize(8),
            Arbitraries.integers().between(0, 100_000),
            Arbitraries.bigDecimals().between(BigDecimal("0.01"), BigDecimal(10_000)).ofScale(4),
        ).`as` { orderSpecs, cost, rate -> ShipmentSpec(orderSpecs, cost, rate) }
    }

    @Property
    fun `JPY shares sum to the shipment cost exactly`(@ForAll("shipments") spec: ShipmentSpec) {
        val dto = spec.dto()

        dto.shippingCostJpy?.let { cost ->
            assertThat(dto.billableOrders().sumOf { requireNotNull(it.shippingCostShareJpy) }).isEqualTo(cost)
        }
    }

    @Property
    fun `EUR shares sum to the shipment cost exactly`(@ForAll("shipments") spec: ShipmentSpec) {
        val dto = spec.dto()

        dto.shippingCostEur?.let { cost ->
            val sum = dto.billableOrders().fold(BigDecimal.ZERO) { total, order -> total + requireNotNull(order.shippingCostShareEur) }
            assertThat(sum).isEqualByComparingTo(cost)
        }
    }

    @Property
    fun `no share is ever negative and a weightless order pays nothing`(@ForAll("shipments") spec: ShipmentSpec) {
        val dto = spec.dto()

        dto.billableOrders().filter { it.shippingCostShareJpy != null }.forEach { order ->
            assertThat(order.shippingCostShareJpy).isGreaterThanOrEqualTo(0)
            assertThat(order.shippingCostShareEur).isGreaterThanOrEqualTo(BigDecimal.ZERO)
            if (requireNotNull(order.orderWeightGrams).signum() == 0) {
                assertThat(order.shippingCostShareJpy).isZero()
                assertThat(order.shippingCostShareEur).isEqualByComparingTo(BigDecimal.ZERO)
            }
        }
    }

    @Property
    fun `a share is within one yen of the exact proportional amount`(@ForAll("shipments") spec: ShipmentSpec) {
        val dto = spec.dto()

        dto.shippingCostJpy?.let { cost ->
            dto.billableOrders().forEach { order ->
                val exact = BigDecimal(cost) * requireNotNull(order.orderWeightGrams)
                val share = BigDecimal(requireNotNull(order.shippingCostShareJpy)) * dto.totalWeightGrams
                assertThat((exact - share).abs()).isLessThanOrEqualTo(dto.totalWeightGrams)
            }
        }
    }

    @Property
    fun `cancelled orders have no share`(@ForAll("shipments") spec: ShipmentSpec) {
        spec.dto().orders.filter { it.status == OrderStatus.CANCELLED }.forEach { order ->
            assertThat(order.shippingCostShareJpy).isNull()
            assertThat(order.shippingCostShareEur).isNull()
            assertThat(order.orderWeightGrams).isNull()
        }
    }

    @Property
    fun `cancelled orders never affect other orders or the total weight`(@ForAll("shipments") spec: ShipmentSpec) {
        val orderEntities = spec.orderEntities()
        val withCancelled = spec.dtoOf(orderEntities)
        val withoutCancelled = spec.dtoOf(orderEntities.filter { it.status != OrderStatus.CANCELLED })

        assertThat(withCancelled.totalWeightGrams).isEqualByComparingTo(withoutCancelled.totalWeightGrams)
        assertThat(withCancelled.billableOrders().map { Triple(it.id, it.shippingCostShareJpy, it.shippingCostShareEur) })
            .containsExactlyElementsOf(withoutCancelled.orders.map { Triple(it.id, it.shippingCostShareJpy, it.shippingCostShareEur) })
    }

    @Property
    fun `shares do not depend on the order of the list`(@ForAll("shipments") spec: ShipmentSpec, @ForAll random: Random) {
        val orderEntities = spec.orderEntities()

        assertThat(spec.dtoOf(orderEntities.shuffled(random)).sharesById()).isEqualTo(spec.dtoOf(orderEntities).sharesById())
    }

    @Property
    fun `a shipment with no weight has no quote and no shares`(@ForAll("shipments") spec: ShipmentSpec) {
        val dto = spec.zeroWeight.dto()

        assertThat(dto.shippingCostJpy).isNull()
        dto.orders.forEach { assertThat(it.shippingCostShareJpy).isNull() }
    }
}
