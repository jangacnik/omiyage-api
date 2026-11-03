package com.omiyage.backend.shipment

import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import net.jqwik.api.constraints.IntRange
import org.assertj.core.api.Assertions.assertThat
import java.math.BigDecimal
import java.util.Random

class ShippingTierSelectionProperties {

    private fun tier(maxWeightGrams: Int, costJpy: Int) = ShippingRate(
        destination = ShipmentDestination.AUSTRIA,
        shippingMethod = ShippingMethod.EMS,
        maxWeightGrams = maxWeightGrams,
        costJpy = costJpy,
        estimatedDays = 7,
    )

    @Provide
    fun ladders(): Arbitrary<List<ShippingRate>> =
        Arbitraries.integers().between(1, 100_000).set().ofMaxSize(12).flatMap { weights ->
            Arbitraries.integers().between(0, 100_000).list().ofSize(weights.size).map { costs ->
                weights.sorted().zip(costs.sorted()) { weight, cost -> tier(weight, cost) }
            }
        }

    @Provide
    fun realGrams(): Arbitrary<BigDecimal> = Arbitraries.integers().between(0, 3)
        .flatMap { scale -> Arbitraries.bigDecimals().between(BigDecimal.ZERO, BigDecimal(5_000_000)).ofScale(scale) }

    @Property
    fun `picks the lightest tier that covers the weight`(
        @ForAll("ladders") ladder: List<@JvmSuppressWildcards ShippingRate>,
        @ForAll @IntRange(min = 1, max = 100_001) grams: Int,
    ) {
        val expected = ladder.filter { it.maxWeightGrams >= grams }.minByOrNull { it.maxWeightGrams }

        assertThat(lightestCoveringTier(ladder, grams)).isSameAs(expected)
    }

    @Property
    fun `no tier means the weight exceeds every tier`(
        @ForAll("ladders") ladder: List<@JvmSuppressWildcards ShippingRate>,
        @ForAll @IntRange(min = 1, max = 100_001) grams: Int,
    ) {
        val found = lightestCoveringTier(ladder, grams)

        assertThat(found == null).isEqualTo(ladder.all { it.maxWeightGrams < grams })
    }

    @Property
    fun `selection does not depend on the input order once sorted`(
        @ForAll("ladders") ladder: List<@JvmSuppressWildcards ShippingRate>,
        @ForAll @IntRange(min = 1, max = 100_001) grams: Int,
        @ForAll random: Random,
    ) {
        val resorted = ladder.shuffled(random).sortedBy { it.maxWeightGrams }

        assertThat(lightestCoveringTier(resorted, grams)).isSameAs(lightestCoveringTier(ladder, grams))
    }

    @Property
    fun `with non-decreasing prices a heavier parcel never costs less`(
        @ForAll("ladders") ladder: List<@JvmSuppressWildcards ShippingRate>,
        @ForAll @IntRange(min = 1, max = 100_001) lighter: Int,
        @ForAll @IntRange(min = 0, max = 100_000) extra: Int,
    ) {
        val lighterTier = lightestCoveringTier(ladder, lighter)
        val heavierTier = lightestCoveringTier(ladder, lighter + extra)

        if (lighterTier != null && heavierTier != null) {
            assertThat(heavierTier.costJpy).isGreaterThanOrEqualTo(lighterTier.costJpy)
        }
    }

    @Property
    fun `a tier covers its exact limit and one gram more moves to the next tier`(
        @ForAll("ladders") ladder: List<@JvmSuppressWildcards ShippingRate>,
    ) {
        ladder.forEachIndexed { index, tier ->
            assertThat(lightestCoveringTier(ladder, tier.maxWeightGrams)).isSameAs(tier)
            assertThat(lightestCoveringTier(ladder, tier.maxWeightGrams + 1)).isSameAs(ladder.getOrNull(index + 1))
        }
    }

    @Property
    fun `billed grams round up by less than one gram`(@ForAll("realGrams") real: BigDecimal) {
        val billed = BigDecimal(billedGrams(real))

        assertThat(billed).isGreaterThanOrEqualTo(real)
        assertThat(billed).isLessThan(real + BigDecimal.ONE)
    }
}
