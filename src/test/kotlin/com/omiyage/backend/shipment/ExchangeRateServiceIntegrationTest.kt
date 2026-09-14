package com.omiyage.backend.shipment

import com.omiyage.backend.PostgresIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

@SpringBootTest
@Transactional
class ExchangeRateServiceIntegrationTest : PostgresIntegrationTest() {

    @Autowired
    lateinit var service: ExchangeRateService

    @Test
    fun `admin can set a new rate and it's used immediately`() {
        service.setRate(BigDecimal("100.0000"))

        assertEquals(BigDecimal("100.0000"), service.getRate())
    }

    @Test
    fun `converts JPY to EUR including the hidden 15 percent markup`() {
        service.setRate(BigDecimal("100.0000"))

        // 4400 JPY at 100 JPY/EUR = 44.00 EUR, +15% markup = 50.60 EUR.
        assertEquals(BigDecimal("50.60"), service.toEurWithMarkup(4400))
    }

    @Test
    fun `rounds the converted amount to two decimal places`() {
        service.setRate(BigDecimal("150.0000"))

        // 1000 / 150 = 6.666667, * 1.15 = 7.66667... -> rounds to 7.67.
        assertEquals(BigDecimal("7.67"), service.toEurWithMarkup(1000))
    }

    @Test
    fun `toEur converts without any markup, for the admin's own cost-basis bookkeeping`() {
        service.setRate(BigDecimal("150.0000"))

        assertEquals(BigDecimal("6.67"), service.toEur(BigDecimal("1000")))
    }
}
