package com.omiyage.backend.shipment

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@Service
class ShippingRateService(private val rates: ShippingRateRepository) {

    @Transactional(readOnly = true)
    fun listAll(): List<ShippingRateDto> =
        rates.findAllByOrderByDestinationAscShippingMethodAscMaxWeightGramsAsc().map { it.toDto() }

    @Transactional
    fun create(request: ShippingRateRequest): ShippingRateDto = rates.save(
        ShippingRate(
            destination = request.destination,
            shippingMethod = request.shippingMethod,
            maxWeightGrams = request.maxWeightGrams,
            costJpy = request.costJpy,
            estimatedDays = request.estimatedDays,
        ),
    ).toDto()

    @Transactional
    fun update(id: UUID, request: ShippingRateRequest): ShippingRateDto {
        val rate = findOrThrow(id)
        rate.destination = request.destination
        rate.shippingMethod = request.shippingMethod
        rate.maxWeightGrams = request.maxWeightGrams
        rate.costJpy = request.costJpy
        rate.estimatedDays = request.estimatedDays
        return rate.toDto()
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
