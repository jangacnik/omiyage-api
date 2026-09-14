package com.omiyage.backend.shipment

import jakarta.validation.constraints.DecimalMin
import java.math.BigDecimal

data class ExchangeRateDto(val jpyPerEur: BigDecimal)

data class SetExchangeRateRequest(@field:DecimalMin(value = "0.01") val jpyPerEur: BigDecimal)

data class ShippingExchangeRateDto(val shippingJpyPerEur: BigDecimal)

data class SetShippingExchangeRateRequest(@field:DecimalMin(value = "0.01") val shippingJpyPerEur: BigDecimal)
