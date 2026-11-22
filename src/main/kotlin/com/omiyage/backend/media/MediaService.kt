package com.omiyage.backend.media

import arrow.core.Either
import arrow.core.raise.Raise
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import com.omiyage.backend.errors.DomainError
import com.omiyage.backend.errors.MediaError
import com.omiyage.backend.security.MeishiPrincipal
import com.omiyage.backend.security.Role
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import kotlin.jvm.optionals.getOrNull
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

/** Image bytes and the content type to serve them with. */
class StoredImage(val contentType: String, val bytes: ByteArray)

@Service
class MediaService(private val repository: MediaRepository, private val store: ObjectStore) {

    private val events = LoggerFactory.getLogger("events")

    fun upload(file: MultipartFile, uploader: MeishiPrincipal): Either<DomainError, String> = either {
        ensure(!file.isEmpty) { MediaError.EmptyFile }
        if (uploader.role != Role.ADMIN) requireWithinDailyQuota(uploader.id)

        val bytes = file.bytes
        val contentType = ensureNotNull(sniffContentType(bytes)) { MediaError.ContentTypeMismatch }
        ensure(contentType == file.contentType) { MediaError.ContentTypeMismatch }

        val key = "uploads/${UUID.randomUUID()}.${contentType.substringAfter('/')}"
        store.put(key, contentType, bytes)
        repository.save(Media(objectKey = key, contentType = contentType, uploaderId = uploader.id, sizeBytes = bytes.size.toLong()))
        events.info("media_uploaded key={} contentType={} sizeBytes={} uploaderId={}", key, contentType, bytes.size, uploader.id)
        key
    }

    fun get(key: String): Either<DomainError, StoredImage> = either {
        val media = ensureNotNull(repository.findById(key).getOrNull()) { MediaError.ImageNotFound }
        val bytes = ensureNotNull(store.get(key) ?: media.data) { MediaError.ImageNotFound }
        StoredImage(media.contentType, bytes)
    }

    fun purgeUnreferencedBefore(cutoff: OffsetDateTime): Int {
        val keys = repository.findUnreferencedKeysBefore(cutoff)
        keys.forEach(store::delete)
        repository.deleteAllByIdInBatch(keys)
        return keys.size
    }

    fun moveLegacyImagesToObjectStore(): Int {
        val keys = repository.findKeysWithLegacyData()
        keys.forEach { key ->
            val media = repository.findById(key).orElseThrow()
            store.put(key, media.contentType, checkNotNull(media.data))
            repository.clearLegacyData(key)
        }
        return keys.size
    }

    fun requireExisting(keys: Collection<String>): Either<DomainError, Unit> = either {
        keys.forEach { key -> ensure(isKnownImageKey(key)) { MediaError.UnknownImageKey(key) } }
    }

    // Keys already stored on an item may predate the media table (object-storage era) and are
    // echoed back unchanged on edits, so a key that is already referenced stays valid.
    private fun isKnownImageKey(key: String): Boolean =
        (IMAGE_KEY_FORMAT.matches(key) && repository.existsById(key)) || repository.isReferenced(key)

    private fun Raise<DomainError>.requireWithinDailyQuota(userId: UUID) {
        val uploadedToday = repository.countByUploaderIdAndCreatedAtAfter(userId, OffsetDateTime.now().minusDays(1))
        ensure(uploadedToday < DAILY_UPLOAD_QUOTA_PER_USER) { MediaError.DailyQuotaReached }
    }
}
