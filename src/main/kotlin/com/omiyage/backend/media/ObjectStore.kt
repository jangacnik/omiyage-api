package com.omiyage.backend.media

/** Holds image bytes; the media table keeps only their metadata. */
interface ObjectStore {
    fun put(key: String, contentType: String, bytes: ByteArray)

    fun get(key: String): ByteArray?

    fun delete(key: String)
}
