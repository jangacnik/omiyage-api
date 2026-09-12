package com.omiyage.backend.shipment

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

// Any authenticated user can read the raw rate - it's used client-side for the plain JPY<->EUR
// display toggle (no markup there, unlike the shipping-cost conversion in ShipmentDtos.toDto).
@RestController
@RequestMapping("/api/exchange-rate")
class ExchangeRateController(private val service: ExchangeRateService) {

    @GetMapping
    fun get(): ExchangeRateDto = ExchangeRateDto(service.getRate())
}
