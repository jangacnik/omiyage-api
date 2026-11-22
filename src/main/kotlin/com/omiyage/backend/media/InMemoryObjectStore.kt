package com.omiyage.backend.media

import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/** For local dev and tests only: images live until the app stops. */
@Component
@Profile("dev | test")
class InMemoryObjectStore : ObjectStore {
    private val objects = ConcurrentHashMap<String, ByteArray>()

    override fun put(key: String, contentType: String, bytes: ByteArray) {
        objects[key] = bytes
    }

    override fun get(key: String): ByteArray? = objects[key]

    override fun delete(key: String) {
        objects.remove(key)
    }
}
