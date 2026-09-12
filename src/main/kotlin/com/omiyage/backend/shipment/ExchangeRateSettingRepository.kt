package com.omiyage.backend.shipment

import org.springframework.data.jpa.repository.JpaRepository

interface ExchangeRateSettingRepository : JpaRepository<ExchangeRateSetting, Short>
