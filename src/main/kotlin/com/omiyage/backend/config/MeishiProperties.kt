package com.omiyage.backend.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "omiyage.meishi")
data class MeishiProperties(
    val jwtSecret: String,
    val apiUrl: String,
)
