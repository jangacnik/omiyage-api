package com.omiyage.backend.config

import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

/** One startup line: profile, version, migration count. Never the resolved configuration. */
@Component
class StartupLogger(private val environment: Environment, private val flyway: Flyway) {

    private val log = LoggerFactory.getLogger("startup")

    @EventListener(ApplicationReadyEvent::class)
    fun logStartup() {
        val version = javaClass.`package`?.implementationVersion ?: "dev"
        log.info(
            "started profiles={} version={} migrations={}",
            environment.activeProfiles.joinToString(",").ifEmpty { "default" }, version, flyway.info().applied().size,
        )
    }
}
