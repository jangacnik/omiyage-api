package com.omiyage.backend.shipment

import jakarta.validation.Validation
import net.jqwik.api.Arbitraries
import net.jqwik.api.Arbitrary
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.Provide
import net.jqwik.api.constraints.IntRange
import org.assertj.core.api.Assertions.assertThat
import java.math.BigDecimal
import java.math.RoundingMode

class CurrencyConversionProperties {

    private val validator = Validation.buildDefaultValidatorFactory().validator

    @Provide
    fun rates(): Arbitrary<BigDecimal> =
        Arbitraries.bigDecimals().between(BigDecimal("0.01"), BigDecimal(10_000)).ofScale(4)

    @Provide
    fun amounts(): Arbitrary<BigDecimal> =
        Arbitraries.bigDecimals().between(BigDecimal.ZERO, BigDecimal(10_000_000)).ofScale(2)

    @Provide
    fun acceptedRates(): Arbitrary<BigDecimal> =
        Arbitraries.bigDecimals().between(BigDecimal("0.01"), BigDecimal("999999.9999")).ofScale(4)

    @Provide
    fun rejectedRates(): Arbitrary<BigDecimal> = Arbitraries.oneOf(
        Arbitraries.bigDecimals().between(BigDecimal(-10_000_000), BigDecimal("0.0099")).ofScale(4),
        Arbitraries.bigDecimals().between(BigDecimal(1_000_000), BigDecimal("1E+30")).ofScale(4),
    )

    @Property
    fun `toEur is monotonic in the amount`(
        @ForAll("amounts") a: BigDecimal,
        @ForAll("amounts") b: BigDecimal,
        @ForAll("rates") rate: BigDecimal,
    ) {
        assertThat(jpyToEur(a.min(b), rate)).isLessThanOrEqualTo(jpyToEur(a.max(b), rate))
    }

    @Property
    fun `toEur of a sum is within a cent of the sum of toEur`(
        @ForAll("amounts") a: BigDecimal,
        @ForAll("amounts") b: BigDecimal,
        @ForAll("rates") rate: BigDecimal,
    ) {
        val difference = (jpyToEur(a, rate) + jpyToEur(b, rate) - jpyToEur(a + b, rate)).abs()

        assertThat(difference).isLessThanOrEqualTo(BigDecimal("0.01"))
    }

    @Property
    fun `zero converts to zero`(@ForAll("rates") rate: BigDecimal) {
        assertThat(jpyToEur(BigDecimal.ZERO, rate)).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(jpyToEurWithMarkup(0, rate)).isEqualByComparingTo(BigDecimal.ZERO)
    }

    @Property
    fun `the markup is monotonic in the amount`(
        @ForAll @IntRange(min = 0, max = 10_000_000) a: Int,
        @ForAll @IntRange(min = 0, max = 10_000_000) b: Int,
        @ForAll("rates") rate: BigDecimal,
    ) {
        assertThat(jpyToEurWithMarkup(minOf(a, b), rate)).isLessThanOrEqualTo(jpyToEurWithMarkup(maxOf(a, b), rate))
    }

    @Property
    fun `the markup never reduces the price`(
        @ForAll @IntRange(min = 0, max = 10_000_000) amount: Int,
        @ForAll("rates") rate: BigDecimal,
    ) {
        val withoutMarkup = BigDecimal(amount).divide(rate, 2, RoundingMode.HALF_UP)

        assertThat(jpyToEurWithMarkup(amount, rate)).isGreaterThanOrEqualTo(withoutMarkup)
    }

    @Property
    fun `rates the column can hold pass validation`(@ForAll("acceptedRates") rate: BigDecimal) {
        assertThat(validator.validate(SetExchangeRateRequest(rate))).isEmpty()
        assertThat(validator.validate(SetShippingExchangeRateRequest(rate))).isEmpty()
    }

    @Property
    fun `zero, negative and huge rates fail validation`(@ForAll("rejectedRates") rate: BigDecimal) {
        assertThat(validator.validate(SetExchangeRateRequest(rate))).isNotEmpty()
        assertThat(validator.validate(SetShippingExchangeRateRequest(rate))).isNotEmpty()
    }
}
