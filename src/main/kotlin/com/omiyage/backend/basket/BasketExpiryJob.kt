package com.omiyage.backend.basket

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.OffsetDateTime

@Component
class BasketExpiryJob(
    private val baskets: BasketRepository,
    @Value("\${omiyage.maintenance.basket-ttl-days}") private val ttlDays: Long,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    init {
        require(ttlDays >= 1) { "basket ttl must be at least 1 day" }
    }

    @Scheduled(cron = "0 30 3 * * *")
    @Transactional
    fun purgeExpired() {
        val purged = baskets.purgeUntouchedSince(OffsetDateTime.now(clock).minusDays(ttlDays))
        log.info("basket expiry (untouched for {} days): {} lines", ttlDays, purged)
    }
}
