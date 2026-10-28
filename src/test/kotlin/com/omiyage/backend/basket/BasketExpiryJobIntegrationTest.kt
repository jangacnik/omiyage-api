package com.omiyage.backend.basket

import com.omiyage.backend.PostgresIntegrationTest
import com.omiyage.backend.common.Currency
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@SpringBootTest
@Transactional
class BasketExpiryJobIntegrationTest : PostgresIntegrationTest() {

    @Autowired
    lateinit var baskets: BasketRepository

    private val now = Instant.parse("2026-10-28T03:30:00Z")

    private fun job(ttlDays: Long) = BasketExpiryJob(baskets, ttlDays, Clock.fixed(now, ZoneOffset.UTC))

    private fun line(userId: UUID, touchedDaysAgo: Long) = baskets.saveAndFlush(
        BasketLine(
            userId = userId,
            customTitle = "Figure",
            currency = Currency.JPY,
            updatedAt = OffsetDateTime.ofInstant(now, ZoneOffset.UTC).minusDays(touchedDaysAgo),
        ),
    )

    @Test
    fun `purges baskets untouched past the ttl and keeps active ones`() {
        val stale = UUID.randomUUID()
        val active = UUID.randomUUID()
        line(stale, 8)
        line(active, 6)

        job(7).purgeExpired()

        assertEquals(0, baskets.findByUserIdOrderByCreatedAt(stale).size)
        assertEquals(1, baskets.findByUserIdOrderByCreatedAt(active).size)
    }

    @Test
    fun `a recently touched line keeps the whole basket`() {
        val user = UUID.randomUUID()
        line(user, 30)
        line(user, 1)

        job(7).purgeExpired()

        assertEquals(2, baskets.findByUserIdOrderByCreatedAt(user).size)
    }

    @Test
    fun `ttl is configurable`() {
        val user = UUID.randomUUID()
        line(user, 3)

        job(2).purgeExpired()

        assertEquals(0, baskets.findByUserIdOrderByCreatedAt(user).size)
    }
}
