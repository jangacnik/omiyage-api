package com.omiyage.backend.media

import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component

/** Moves images that were stored in Postgres before object storage into the object store, once, at startup. */
@Component
class LegacyMediaMigration(private val service: MediaService) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        val moved = service.moveLegacyImagesToObjectStore()
        if (moved > 0) log.info("moved {} legacy images from Postgres to the object store", moved)
    }
}
