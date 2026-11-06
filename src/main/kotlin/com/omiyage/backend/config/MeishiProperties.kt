package com.omiyage.backend.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "omiyage.meishi")
data class MeishiProperties(
    val jwtSecret: String,
    val apiUrl: String,
    // Set only during a secret rotation: tokens signed with the old secret stay valid until expiry.
    val jwtSecretPrevious: String? = null,
    // Meishi single sign-on; off unless explicitly enabled and the hosted login URL is known.
    val ssoEnabled: Boolean = false,
    val ssoClientId: String = "omiyage",
    val ssoAuthorizeUrl: String = "",
)
