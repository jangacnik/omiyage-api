package com.omiyage.backend.media

import arrow.core.Either
import com.omiyage.backend.PostgresIntegrationTest
import com.omiyage.backend.errors.DomainError
import com.omiyage.backend.errors.MediaError
import com.omiyage.backend.errors.errorOrFail
import com.omiyage.backend.errors.orFail
import com.omiyage.backend.security.MeishiPrincipal
import com.omiyage.backend.security.Role
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.mock.web.MockMultipartFile
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID

@SpringBootTest
class MediaServiceIntegrationTest : PostgresIntegrationTest() {

    @Autowired
    lateinit var mediaService: MediaService

    @Autowired
    lateinit var mediaRepository: MediaRepository

    @Autowired
    lateinit var objectStore: ObjectStore

    private val jpegBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3)
    private val admin = MeishiPrincipal(UUID.randomUUID(), "root", Role.ADMIN)

    private fun user() = MeishiPrincipal(UUID.randomUUID(), "bob", Role.USER)

    private fun errorOf(block: () -> Either<DomainError, *>): DomainError = block().errorOrFail()

    @Test
    fun `upload stores the file and returns a retrievable key`() {
        val uploader = user()
        val file = MockMultipartFile("file", "photo.jpg", "image/jpeg", jpegBytes)

        val key = mediaService.upload(file, uploader).orFail()

        assertTrue(key.startsWith("uploads/"))
        assertTrue(key.endsWith(".jpeg"))
        val stored = mediaService.get(key).orFail()
        assertEquals("image/jpeg", stored.contentType)
        assertArrayEquals(jpegBytes, stored.bytes)
        assertArrayEquals(jpegBytes, objectStore.get(key))
        val row = mediaRepository.findById(key).orElseThrow()
        assertNull(row.data)
        assertEquals(uploader.id, row.uploaderId)
        assertEquals(jpegBytes.size.toLong(), row.sizeBytes)
    }

    @Test
    fun `get reports an unknown key as not found`() {
        assertEquals(MediaError.ImageNotFound, mediaService.get("uploads/missing.jpeg").errorOrFail())
    }

    @Test
    fun `upload rejects a disallowed content type`() {
        val file = MockMultipartFile("file", "notes.txt", "text/plain", "hello".toByteArray())
        assertEquals(MediaError.ContentTypeMismatch, errorOf { mediaService.upload(file, admin) })
    }

    @Test
    fun `upload rejects text declared as an image`() {
        val file = MockMultipartFile("file", "fake.png", "image/png", "not an image".toByteArray())
        assertEquals(MediaError.ContentTypeMismatch, errorOf { mediaService.upload(file, admin) })
    }

    @Test
    fun `upload rejects an image whose bytes do not match the declared type`() {
        val file = MockMultipartFile("file", "photo.png", "image/png", jpegBytes)
        assertEquals(MediaError.ContentTypeMismatch, errorOf { mediaService.upload(file, admin) })
    }

    @Test
    fun `upload rejects an empty file`() {
        val file = MockMultipartFile("file", "empty.jpg", "image/jpeg", ByteArray(0))
        assertEquals(MediaError.EmptyFile, errorOf { mediaService.upload(file, admin) })
    }

    @Test
    fun `non-admin uploads are limited per day`() {
        val uploader = user()
        val file = MockMultipartFile("file", "photo.jpg", "image/jpeg", jpegBytes)
        repeat(50) { mediaService.upload(file, uploader).orFail() }

        assertEquals(MediaError.DailyQuotaReached, errorOf { mediaService.upload(file, uploader) })
        mediaService.upload(file, admin).orFail()
    }

    @Test
    fun `requireExisting rejects malformed and unknown keys`() {
        val key = mediaService.upload(MockMultipartFile("file", "photo.jpg", "image/jpeg", jpegBytes), admin).orFail()
        mediaService.requireExisting(listOf(key)).orFail()

        val unknown = "uploads/${UUID.randomUUID()}.jpeg"
        assertEquals(MediaError.UnknownImageKey(unknown), errorOf { mediaService.requireExisting(listOf(unknown)) })
        assertEquals(MediaError.UnknownImageKey("../etc/passwd"), errorOf { mediaService.requireExisting(listOf("../etc/passwd")) })
    }

    @Test
    @Transactional
    fun `purge removes old unreferenced media but keeps recent ones`() {
        val oldKey = "uploads/${UUID.randomUUID()}.jpeg"
        mediaRepository.save(Media(oldKey, "image/jpeg", jpegBytes, createdAt = OffsetDateTime.now().minusDays(3)))
        val recentKey = mediaService.upload(MockMultipartFile("file", "photo.jpg", "image/jpeg", jpegBytes), admin).orFail()

        objectStore.put(oldKey, "image/jpeg", jpegBytes)

        mediaService.purgeUnreferencedBefore(OffsetDateTime.now().minusDays(1))

        assertFalse(mediaRepository.existsById(oldKey))
        assertNull(objectStore.get(oldKey))
        assertTrue(mediaRepository.existsById(recentKey))
    }

    @Test
    fun `legacy images stored in Postgres move to the object store and stay readable`() {
        val legacyKey = "uploads/${UUID.randomUUID()}.jpeg"
        mediaRepository.save(Media(legacyKey, "image/jpeg", jpegBytes))

        assertArrayEquals(jpegBytes, mediaService.get(legacyKey).orFail().bytes)
        mediaService.moveLegacyImagesToObjectStore()

        assertArrayEquals(jpegBytes, objectStore.get(legacyKey))
        assertNull(mediaRepository.findById(legacyKey).orElseThrow().data)
        assertArrayEquals(jpegBytes, mediaService.get(legacyKey).orFail().bytes)
    }
}
