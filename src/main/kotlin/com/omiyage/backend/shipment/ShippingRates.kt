package com.omiyage.backend.shipment

enum class ShipmentDestination { SLOVENIA, AUSTRIA }
enum class ShippingMethod { EMS, SMALL_PACKET, SURFACE_MAIL }

/**
 * Japan Post international rates, JPY, by weight bracket (grams -> price for a parcel up to that
 * many grams). Slovenia and Austria are both Japan Post's "Third Zone" (Europe), so for now they
 * share one table each - kept per-destination rather than per-zone so adding a country in a
 * different zone later is just a new table entry, not a restructure.
 *
 * Source: post.japanpost.jp official rate schedules (EMS, Small Packet, Surface Mail), checked
 * 2026-09-12. Japan Post revises these periodically - re-verify against the live rate schedule
 * before trusting this for anything beyond an estimate shown to users.
 */
object ShippingRates {

    private val zone3Ems = listOf(
        500 to 3150, 600 to 3400, 700 to 3650, 800 to 3900, 900 to 4150, 1000 to 4400,
        1250 to 5000, 1500 to 5550, 1750 to 6150, 2000 to 6700, 2500 to 7750, 3000 to 8800,
        3500 to 9850, 4000 to 10900, 4500 to 11950, 5000 to 13000, 5500 to 14050, 6000 to 15100,
        7000 to 17200, 8000 to 19300, 9000 to 21400, 10000 to 23500, 11000 to 25600,
        12000 to 27700, 13000 to 29800, 14000 to 31900, 15000 to 34000, 16000 to 36100,
        17000 to 38200, 18000 to 40300, 19000 to 42400, 20000 to 44500, 21000 to 46600,
        22000 to 48700, 23000 to 50800, 24000 to 52900, 25000 to 55000, 26000 to 57100,
        27000 to 59200, 28000 to 61300, 29000 to 63400, 30000 to 65500,
    )

    private val zone3SmallPacket = listOf(
        100 to 510, 200 to 690, 300 to 870, 400 to 1050, 500 to 1230, 600 to 1410, 700 to 1590,
        800 to 1770, 900 to 1950, 1000 to 2130, 1100 to 2310, 1200 to 2490, 1300 to 2670,
        1400 to 2850, 1500 to 3030, 1600 to 3210, 1700 to 3390, 1800 to 3570, 1900 to 3750,
        2000 to 3930,
    )

    private val zone3SurfaceMail = listOf(
        100 to 480, 250 to 600, 500 to 800, 1000 to 1300, 2000 to 2200,
    )

    private fun tableFor(method: ShippingMethod, destination: ShipmentDestination): List<Pair<Int, Int>> =
        when (destination) {
            ShipmentDestination.SLOVENIA, ShipmentDestination.AUSTRIA -> when (method) {
                ShippingMethod.EMS -> zone3Ems
                ShippingMethod.SMALL_PACKET -> zone3SmallPacket
                ShippingMethod.SURFACE_MAIL -> zone3SurfaceMail
            }
        }

    /** JPY price for [totalGrams] via [method] to [destination], or null if that method doesn't
     *  cover a parcel this heavy (Small Packet and Surface Mail both cap at 2kg). */
    fun priceJpy(method: ShippingMethod, destination: ShipmentDestination, totalGrams: Int): Int? =
        tableFor(method, destination).firstOrNull { (maxGrams, _) -> totalGrams <= maxGrams }?.second
}
