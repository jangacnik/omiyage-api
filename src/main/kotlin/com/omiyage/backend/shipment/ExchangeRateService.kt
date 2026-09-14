package com.omiyage.backend.shipment

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.OffsetDateTime

// Shipping cost is quoted to the admin in JPY but shown to everyone in EUR with a 15% margin baked
// in on top of the day's conversion - the rate itself and this markup must never reach an API
// response verbatim (see AdminExchangeRateController vs. ExchangeRateController: the admin can see
// and edit the base rate they set, but the +15% multiplier below is never serialized anywhere).
private val SHIPPING_MARKUP = BigDecimal("1.15")
private const val SETTING_ID: Short = 1

@Service
class ExchangeRateService(private val settings: ExchangeRateSettingRepository) {

    @Transactional(readOnly = true)
    fun getRate(): BigDecimal = setting().jpyPerEur

    @Transactional
    fun setRate(jpyPerEur: BigDecimal) {
        val setting = setting()
        setting.jpyPerEur = jpyPerEur
        setting.updatedAt = OffsetDateTime.now()
    }

    /** Bare JPY -> EUR conversion at the admin-set rate, no markup - for converting the admin's own
     *  JPY cost-basis (item bought-price, inventory bought-price) into EUR for profit bookkeeping.
     *  Not for anything customer-facing - see toEurWithMarkup for that. */
    @Transactional(readOnly = true)
    fun toEur(jpyAmount: BigDecimal): BigDecimal =
        jpyAmount.divide(getRate(), 2, RoundingMode.HALF_UP)

    /** Converts a JPY shipping cost to EUR, including the hidden markup - never expose the
     *  intermediate (pre-markup) amount anywhere the markup could be reverse-engineered. */
    @Transactional(readOnly = true)
    fun toEurWithMarkup(jpyAmount: Int): BigDecimal =
        BigDecimal(jpyAmount).divide(getRate(), 6, RoundingMode.HALF_UP)
            .multiply(SHIPPING_MARKUP)
            .setScale(2, RoundingMode.HALF_UP)

    private fun setting(): ExchangeRateSetting =
        settings.findById(SETTING_ID).orElseThrow { IllegalStateException("exchange rate setting row is missing") }
}
