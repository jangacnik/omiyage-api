package com.omiyage.backend.shipment

import jakarta.validation.Valid
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/admin/exchange-rate")
class AdminExchangeRateController(private val service: ExchangeRateService) {

    @GetMapping
    fun get(): ExchangeRateDto = ExchangeRateDto(service.getRate())

    @PutMapping
    fun set(@Valid @RequestBody request: SetExchangeRateRequest): ExchangeRateDto {
        service.setRate(request.jpyPerEur)
        return ExchangeRateDto(service.getRate())
    }

    @GetMapping("/shipping")
    fun getShipping(): ShippingExchangeRateDto = ShippingExchangeRateDto(service.getShippingRate())

    @PutMapping("/shipping")
    fun setShipping(@Valid @RequestBody request: SetShippingExchangeRateRequest): ShippingExchangeRateDto {
        service.setShippingRate(request.shippingJpyPerEur)
        return ShippingExchangeRateDto(service.getShippingRate())
    }
}
