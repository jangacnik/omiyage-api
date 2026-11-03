package com.omiyage.backend.order

import com.omiyage.backend.common.Currency
import com.omiyage.backend.shipment.jpyToEur
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.Combinators
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import org.assertj.core.api.Assertions.assertThat
import java.math.BigDecimal
import java.util.Random
import java.util.UUID

class OrderMathsProperties {

    data class ItemSpec(val weight: BigDecimal?, val quantity: Int, val sold: BigDecimal?, val bought: BigDecimal?)

    // Mixed scales on purpose: the profit code calls out scale sensitivity.
    private fun money(max: Int): Arbitrary<BigDecimal> = Arbitraries.integers().between(0, 3)
        .flatMap { scale -> Arbitraries.bigDecimals().between(BigDecimal.ZERO, BigDecimal(max)).ofScale(scale) }

    @Provide
    fun items(): Arbitrary<List<ItemSpec>> = Combinators.combine(
        money(5000).injectNull(0.2),
        Arbitraries.integers().between(1, 1000),
        money(1000).injectNull(0.2),
        money(200_000).injectNull(0.2),
    ).`as` { weight, quantity, sold, bought -> ItemSpec(weight, quantity, sold, bought) }.list().ofMaxSize(8)

    @Provide
    fun rates(): Arbitrary<BigDecimal> = Arbitraries.bigDecimals().between(BigDecimal("0.01"), BigDecimal(10_000)).ofScale(4)

    private fun orderOf(specs: List<ItemSpec>): Order {
        val order = Order(id = UUID.randomUUID(), userId = UUID.randomUUID())
        specs.forEach { spec ->
            order.items.add(
                OrderItem(
                    id = UUID.randomUUID(),
                    order = order,
                    customTitle = "item",
                    currency = Currency.EUR,
                    quantity = spec.quantity,
                    weight = spec.weight,
                    soldPrice = spec.sold,
                    soldPriceCurrency = spec.sold?.let { Currency.EUR },
                    actualPrice = spec.bought,
                    actualPriceCurrency = spec.bought?.let { Currency.JPY },
                ),
            )
        }
        return order
    }

    @Property
    fun `total weight is the sum of weight times quantity, missing weight counting as zero`(@ForAll("items") specs: List<ItemSpec>) {
        val expected = specs.sumOf { (it.weight ?: BigDecimal.ZERO) * BigDecimal(it.quantity) }

        assertThat(orderOf(specs).totalWeightGrams()).isEqualByComparingTo(expected)
    }

    @Property
    fun `total weight does not depend on item order`(@ForAll("items") specs: List<ItemSpec>, @ForAll random: Random) {
        assertThat(orderOf(specs.shuffled(random)).totalWeightGrams()).isEqualByComparingTo(orderOf(specs).totalWeightGrams())
    }

    @Property
    fun `merging two orders adds their weights`(@ForAll("items") a: List<ItemSpec>, @ForAll("items") b: List<ItemSpec>) {
        assertThat(orderOf(a + b).totalWeightGrams())
            .isEqualByComparingTo(orderOf(a).totalWeightGrams() + orderOf(b).totalWeightGrams())
    }

    @Property
    fun `item profit is null exactly when a price is missing, otherwise scale 2`(
        @ForAll("items") specs: List<ItemSpec>,
        @ForAll("rates") rate: BigDecimal,
    ) {
        val dtos = orderOf(specs).toDto(includeProfit = true) { jpyToEur(it, rate) }.items

        specs.zip(dtos).forEach { (spec, dto) ->
            if (spec.sold == null || spec.bought == null) {
                assertThat(dto.profit).isNull()
            } else {
                assertThat(dto.profit!!.scale()).isEqualTo(2)
            }
        }
    }

    @Property
    fun `item profit is within half a cent of the exact margin times quantity`(
        @ForAll("items") specs: List<ItemSpec>,
        @ForAll("rates") rate: BigDecimal,
    ) {
        val dtos = orderOf(specs).toDto(includeProfit = true) { jpyToEur(it, rate) }.items

        specs.zip(dtos).filter { (spec, _) -> spec.sold != null && spec.bought != null }.forEach { (spec, dto) ->
            val exact = (spec.sold!! - jpyToEur(spec.bought!!, rate)) * BigDecimal(spec.quantity)
            assertThat((dto.profit!! - exact).abs()).isLessThanOrEqualTo(BigDecimal("0.005"))
        }
    }

    @Property
    fun `order profit is the sum of item profits and hidden without includeProfit`(
        @ForAll("items") specs: List<ItemSpec>,
        @ForAll("rates") rate: BigDecimal,
    ) {
        val order = orderOf(specs)
        val dto = order.toDto(includeProfit = true) { jpyToEur(it, rate) }

        assertThat(dto.profit).isEqualByComparingTo(dto.items.mapNotNull { it.profit }.fold(BigDecimal.ZERO, BigDecimal::add))
        assertThat(order.toDto().profit).isNull()
        assertThat(order.toDto().items).allMatch { it.profit == null }
    }
}
