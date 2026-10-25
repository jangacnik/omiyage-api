package com.omiyage.backend.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "omiyage.meishi")
data class MeishiProperties(
    val jwtSecret: String,
    val apiUrl: String,
    // Set only during a secret rotation: tokens signed with the old secret stay valid until expiry.
    val jwtSecretPrevious: String? = null,
)
