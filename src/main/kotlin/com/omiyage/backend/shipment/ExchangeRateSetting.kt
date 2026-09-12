package com.omiyage.backend.shipment

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.OffsetDateTime

// Singleton row (id is always 1) - there is exactly one JPY/EUR rate for the whole app, not one
// per something else, so a full table with a lookup key would be overkill.
@Entity
@Table(name = "exchange_rate_setting")
class ExchangeRateSetting(
    @Id
    val id: Short = 1,

    @Column(name = "jpy_per_eur", nullable = false)
    var jpyPerEur: BigDecimal,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime = OffsetDateTime.now(),
)
