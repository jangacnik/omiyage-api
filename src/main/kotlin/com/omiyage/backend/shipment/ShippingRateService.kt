package com.omiyage.backend.shipment

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@Service
class ShippingRateService(private val rates: ShippingRateRepository, private val exchangeRates: ExchangeRateService) {

    @Transactional(readOnly = true)
    fun listAll(): List<ShippingRateDto> =
        rates.findAllByOrderByDestinationAscShippingMethodAscMaxWeightGramsAsc().map { it.toDto(exchangeRates::toEurWithMarkup) }

    @Transactional
    fun create(request: ShippingRateRequest): ShippingRateDto {
        requireNoDuplicate(request, excludingId = null)
        return rates.save(
            ShippingRate(
                destination = request.destination,
                shippingMethod = request.shippingMethod,
                maxWeightGrams = request.maxWeightGrams,
                costJpy = request.costJpy,
                estimatedDays = request.estimatedDays,
            ),
        ).toDto(exchangeRates::toEurWithMarkup)
    }

    @Transactional
    fun update(id: UUID, request: ShippingRateRequest): ShippingRateDto {
        val rate = findOrThrow(id)
        requireNoDuplicate(request, excludingId = id)
        rate.destination = request.destination
        rate.shippingMethod = request.shippingMethod
        rate.maxWeightGrams = request.maxWeightGrams
        rate.costJpy = request.costJpy
        rate.estimatedDays = request.estimatedDays
        return rate.toDto(exchangeRates::toEurWithMarkup)
    }

    private fun requireNoDuplicate(request: ShippingRateRequest, excludingId: UUID?) {
        val existing = rates.findByDestinationAndShippingMethodAndMaxWeightGrams(
            request.destination,
            request.shippingMethod,
            request.maxWeightGrams,
        )
        if (existing != null && existing.id != excludingId) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "a rate already exists for this destination, method and weight tier")
        }
    }

    @Transactional
    fun delete(id: UUID) {
        if (!rates.existsById(id)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "shipping rate not found")
        }
        rates.deleteById(id)
    }

    /** Cheapest-tier lookup: the lightest configured tier that still covers [totalGrams], or null
     *  if no tier for this [method]/[destination] combination covers a parcel this heavy. */
    @Transactional(readOnly = true)
    fun priceFor(method: ShippingMethod, destination: ShipmentDestination, totalGrams: Int): ShippingQuote? =
        rates.findByDestinationAndShippingMethodOrderByMaxWeightGramsAsc(destination, method)
            .firstOrNull { it.maxWeightGrams >= totalGrams }
            ?.let { ShippingQuote(it.costJpy, it.estimatedDays) }

    private fun findOrThrow(id: UUID): ShippingRate =
        rates.findById(id).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND, "shipping rate not found") }
}

data class ShippingQuote(val costJpy: Int, val estimatedDays: Int)
