package com.omiyage.backend.shipment

import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import com.omiyage.backend.errors.DomainError
import com.omiyage.backend.errors.ShipmentError
import com.omiyage.backend.errors.transactionalEither
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID
import kotlin.jvm.optionals.getOrNull

@Service
class ShippingRateService(private val rates: ShippingRateRepository, private val exchangeRates: ExchangeRateService) {

    @Transactional(readOnly = true)
    fun listAll(): List<ShippingRateDto> =
        rates.findAllByOrderByDestinationAscShippingMethodAscMaxWeightGramsAsc().map { it.toDto(exchangeRates::toEurWithMarkup) }

    @Transactional
    fun create(request: ShippingRateRequest): Either<DomainError, ShippingRateDto> = transactionalEither {
        requireNoDuplicate(request, excludingId = null)
        rates.save(
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
    fun update(id: UUID, request: ShippingRateRequest): Either<DomainError, ShippingRateDto> = transactionalEither {
        val rate = findOrRaise(id)
        requireNoDuplicate(request, excludingId = id)
        rate.destination = request.destination
        rate.shippingMethod = request.shippingMethod
        rate.maxWeightGrams = request.maxWeightGrams
        rate.costJpy = request.costJpy
        rate.estimatedDays = request.estimatedDays
        rate.toDto(exchangeRates::toEurWithMarkup)
    }

    private fun Raise<DomainError>.requireNoDuplicate(request: ShippingRateRequest, excludingId: UUID?) {
        val existing = rates.findByDestinationAndShippingMethodAndMaxWeightGrams(
            request.destination,
            request.shippingMethod,
            request.maxWeightGrams,
        )
        ensure(existing == null || existing.id == excludingId) { ShipmentError.ShippingRateExists }
    }

    @Transactional
    fun delete(id: UUID): Either<DomainError, Unit> = transactionalEither {
        ensure(rates.existsById(id)) { ShipmentError.ShippingRateNotFound }
        rates.deleteById(id)
    }

    /** Cheapest-tier lookup: the lightest configured tier that still covers [totalGrams], or null
     *  if no tier for this [method]/[destination] combination covers a parcel this heavy. */
    @Transactional(readOnly = true)
    fun priceFor(method: ShippingMethod, destination: ShipmentDestination, totalGrams: Int): ShippingQuote? =
        lightestCoveringTier(rates.findByDestinationAndShippingMethodOrderByMaxWeightGramsAsc(destination, method), totalGrams)
            ?.let { ShippingQuote(it.costJpy, it.estimatedDays) }

    private fun Raise<DomainError>.findOrRaise(id: UUID): ShippingRate =
        ensureNotNull(rates.findById(id).getOrNull()) { ShipmentError.ShippingRateNotFound }
}

data class ShippingQuote(val costJpy: Int, val estimatedDays: Int)

/** [tiers] must be sorted by ascending maxWeightGrams. */
internal fun lightestCoveringTier(tiers: List<ShippingRate>, totalGrams: Int): ShippingRate? =
    tiers.firstOrNull { it.maxWeightGrams >= totalGrams }

internal fun billedGrams(totalWeight: BigDecimal): Int = totalWeight.setScale(0, RoundingMode.CEILING).toInt()
