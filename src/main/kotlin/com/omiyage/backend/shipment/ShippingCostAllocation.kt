package com.omiyage.backend.shipment

import java.math.BigDecimal

/** Splits [total] minor units across [weights] by largest remainder, so the shares sum to exactly
 *  [total], none is negative and a zero weight gets nothing. Ties go to the earliest weight. */
internal fun allocateByWeight(total: Long, weights: List<BigDecimal>): List<Long> {
    val totalWeight = weights.fold(BigDecimal.ZERO, BigDecimal::add)
    val splits = weights.map { (BigDecimal.valueOf(total) * it).divideAndRemainder(totalWeight) }
    val floors = splits.map { it[0].toLong() }
    val unallocated = (total - floors.sum()).toInt()
    val roundedUp = splits.indices.sortedByDescending { splits[it][1] }.take(unallocated).toSet()
    return floors.mapIndexed { index, floor -> if (index in roundedUp) floor + 1 else floor }
}
