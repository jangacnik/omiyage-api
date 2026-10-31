package com.omiyage.backend.media

import com.omiyage.backend.security.MeishiPrincipal
import com.omiyage.backend.security.Role
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException
import java.time.OffsetDateTime
import java.util.UUID

private const val DAILY_UPLOAD_QUOTA_PER_USER = 50L
private val IMAGE_KEY_FORMAT = Regex("^uploads/[0-9a-f-]{36}\\.(jpeg|png|webp|gif)$")

private fun sniffContentType(bytes: ByteArray): String? = when {
    bytes.startsWith(0xFF, 0xD8, 0xFF) -> "image/jpeg"
    bytes.startsWith(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> "image/png"
    bytes.startsWith('G'.code, 'I'.code, 'F'.code, '8'.code) -> "image/gif"
    bytes.startsWith('R'.code, 'I'.code, 'F'.code, 'F'.code) && bytes.hasAt(8, "WEBP") -> "image/webp"
    else -> null
}

private fun ByteArray.startsWith(vararg prefix: Int): Boolean =
    size >= prefix.size && prefix.indices.all { this[it] == prefix[it].toByte() }

private fun ByteArray.hasAt(offset: Int, text: String): Boolean =
    size >= offset + text.length && text.indices.all { this[offset + it] == text[it].code.toByte() }

@Service
class MediaService(private val repository: MediaRepository) {

    private val events = LoggerFactory.getLogger("events")

    fun upload(file: MultipartFile, uploader: MeishiPrincipal): String {
        if (file.isEmpty) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "file is empty")
        }
        if (uploader.role != Role.ADMIN) requireWithinDailyQuota(uploader.id)

        val bytes = file.bytes
        val contentType = sniffContentType(bytes)
        if (contentType == null || contentType != file.contentType) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "file content does not match an allowed image type")
        }

        val key = "uploads/${UUID.randomUUID()}.${contentType.substringAfter('/')}"
        repository.save(Media(objectKey = key, contentType = contentType, data = bytes, uploaderId = uploader.id))
        events.info("media_uploaded key={} contentType={} sizeBytes={} uploaderId={}", key, contentType, bytes.size, uploader.id)
        return key
    }

    fun get(key: String): Media =
        repository.findById(key).orElseThrow { ResponseStatusException(HttpStatus.NOT_FOUND) }

    fun requireExisting(keys: Collection<String>) {
        keys.forEach { key ->
            if (!isKnownImageKey(key)) {
                throw ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown image key: $key")
            }
        }
    }

    // Keys already stored on an item may predate the media table (object-storage era) and are
    // echoed back unchanged on edits, so a key that is already referenced stays valid.
    private fun isKnownImageKey(key: String): Boolean =
        (IMAGE_KEY_FORMAT.matches(key) && repository.existsById(key)) || repository.isReferenced(key)

    private fun requireWithinDailyQuota(userId: UUID) {
        val uploadedToday = repository.countByUploaderIdAndCreatedAtAfter(userId, OffsetDateTime.now().minusDays(1))
        if (uploadedToday >= DAILY_UPLOAD_QUOTA_PER_USER) {
            throw ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "daily upload limit reached")
        }
    }
}
