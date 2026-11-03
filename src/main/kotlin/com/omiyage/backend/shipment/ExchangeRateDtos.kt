package com.omiyage.backend.shipment

import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import java.math.BigDecimal

// Largest value the NUMERIC(10, 4) rate columns can hold.
private const val MAX_JPY_PER_EUR = "999999.9999"

data class ExchangeRateDto(val jpyPerEur: BigDecimal)

data class SetExchangeRateRequest(@field:DecimalMin(value = "0.01") @field:DecimalMax(value = MAX_JPY_PER_EUR) val jpyPerEur: BigDecimal)

data class ShippingExchangeRateDto(val shippingJpyPerEur: BigDecimal)

data class SetShippingExchangeRateRequest(@field:DecimalMin(value = "0.01") @field:DecimalMax(value = MAX_JPY_PER_EUR) val shippingJpyPerEur: BigDecimal)
