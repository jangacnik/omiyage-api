package com.omiyage.backend.media

import com.omiyage.backend.PostgresIntegrationTest
import com.omiyage.backend.security.MeishiPrincipal
import com.omiyage.backend.security.Role
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockMultipartFile
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ResponseStatusException
import java.time.OffsetDateTime
import java.util.UUID

@SpringBootTest
class MediaServiceIntegrationTest : PostgresIntegrationTest() {

    @Autowired
    lateinit var mediaService: MediaService

    @Autowired
    lateinit var mediaRepository: MediaRepository

    private val jpegBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3)
    private val admin = MeishiPrincipal(UUID.randomUUID(), "root", Role.ADMIN)

    private fun user() = MeishiPrincipal(UUID.randomUUID(), "bob", Role.USER)

    private fun statusOf(block: () -> Unit) = assertThrows(ResponseStatusException::class.java, block).statusCode

    @Test
    fun `upload stores the file and returns a retrievable key`() {
        val uploader = user()
        val file = MockMultipartFile("file", "photo.jpg", "image/jpeg", jpegBytes)

        val key = mediaService.upload(file, uploader)

        assertTrue(key.startsWith("uploads/"))
        assertTrue(key.endsWith(".jpeg"))
        val stored = mediaService.get(key)
        assertEquals("image/jpeg", stored.contentType)
        assertArrayEquals(jpegBytes, stored.data)
        assertEquals(uploader.id, stored.uploaderId)
        assertEquals(jpegBytes.size.toLong(), stored.sizeBytes)
    }

    @Test
    fun `get throws not found for an unknown key`() {
        assertThrows(ResponseStatusException::class.java) { mediaService.get("uploads/missing.jpeg") }
    }

    @Test
    fun `upload rejects a disallowed content type`() {
        val file = MockMultipartFile("file", "notes.txt", "text/plain", "hello".toByteArray())
        assertEquals(HttpStatus.BAD_REQUEST, statusOf { mediaService.upload(file, admin) })
    }

    @Test
    fun `upload rejects text declared as an image`() {
        val file = MockMultipartFile("file", "fake.png", "image/png", "not an image".toByteArray())
        assertEquals(HttpStatus.BAD_REQUEST, statusOf { mediaService.upload(file, admin) })
    }

    @Test
    fun `upload rejects an image whose bytes do not match the declared type`() {
        val file = MockMultipartFile("file", "photo.png", "image/png", jpegBytes)
        assertEquals(HttpStatus.BAD_REQUEST, statusOf { mediaService.upload(file, admin) })
    }

    @Test
    fun `upload rejects an empty file`() {
        val file = MockMultipartFile("file", "empty.jpg", "image/jpeg", ByteArray(0))
        assertThrows(ResponseStatusException::class.java) { mediaService.upload(file, admin) }
    }

    @Test
    fun `non-admin uploads are limited per day`() {
        val uploader = user()
        val file = MockMultipartFile("file", "photo.jpg", "image/jpeg", jpegBytes)
        repeat(50) { mediaService.upload(file, uploader) }

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, statusOf { mediaService.upload(file, uploader) })
        mediaService.upload(file, admin)
    }

    @Test
    fun `requireExisting rejects malformed and unknown keys`() {
        val key = mediaService.upload(MockMultipartFile("file", "photo.jpg", "image/jpeg", jpegBytes), admin)
        mediaService.requireExisting(listOf(key))

        assertEquals(HttpStatus.BAD_REQUEST, statusOf { mediaService.requireExisting(listOf("uploads/${UUID.randomUUID()}.jpeg")) })
        assertEquals(HttpStatus.BAD_REQUEST, statusOf { mediaService.requireExisting(listOf("../etc/passwd")) })
    }

    @Test
    @Transactional
    fun `purge removes old unreferenced media but keeps recent ones`() {
        val oldKey = "uploads/${UUID.randomUUID()}.jpeg"
        mediaRepository.save(Media(oldKey, "image/jpeg", jpegBytes, createdAt = OffsetDateTime.now().minusDays(3)))
        val recentKey = mediaService.upload(MockMultipartFile("file", "photo.jpg", "image/jpeg", jpegBytes), admin)

        mediaRepository.purgeUnreferencedBefore(OffsetDateTime.now().minusDays(1))

        assertFalse(mediaRepository.existsById(oldKey))
        assertTrue(mediaRepository.existsById(recentKey))
    }
}
