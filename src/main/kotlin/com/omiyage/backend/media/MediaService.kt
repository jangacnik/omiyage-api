package com.omiyage.backend.media

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

private val ALLOWED_CONTENT_TYPES = setOf("image/jpeg", "image/png", "image/webp", "image/gif")

@Service
class MediaService(private val repository: MediaRepository) {

    fun upload(file: MultipartFile): String {
        if (file.isEmpty) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "file is empty")
        }
        val contentType = file.contentType
        if (contentType !in ALLOWED_CONTENT_TYPES) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported content type: $contentType")
        }

        val extension = contentType!!.substringAfter('/')
        val key = "uploads/${UUID.randomUUID()}.$extension"
        repository.save(Media(objectKey = key, contentType = contentType, data = file.bytes))
        return key
    }

    fun get(key: String): Media =
        repository.findById(key).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND) }
}
