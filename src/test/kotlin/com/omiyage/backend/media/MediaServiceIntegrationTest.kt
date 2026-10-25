package com.omiyage.backend.media

import com.omiyage.backend.PostgresIntegrationTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.mock.web.MockMultipartFile
import org.springframework.web.server.ResponseStatusException

@SpringBootTest
class MediaServiceIntegrationTest : PostgresIntegrationTest() {

    @Autowired
    lateinit var mediaService: MediaService

    @Test
    fun `upload stores the file and returns a retrievable key`() {
        val bytes = "fake-bytes".toByteArray()
        val file = MockMultipartFile("file", "photo.jpg", "image/jpeg", bytes)

        val key = mediaService.upload(file)

        assertTrue(key.startsWith("uploads/"))
        assertTrue(key.endsWith(".jpeg"))
        val stored = mediaService.get(key)
        assertEquals("image/jpeg", stored.contentType)
        assertArrayEquals(bytes, stored.data)
    }

    @Test
    fun `get throws not found for an unknown key`() {
        assertThrows(ResponseStatusException::class.java) { mediaService.get("uploads/missing.jpeg") }
    }

    @Test
    fun `upload rejects a disallowed content type`() {
        val file = MockMultipartFile("file", "notes.txt", "text/plain", "hello".toByteArray())
        assertThrows(ResponseStatusException::class.java) { mediaService.upload(file) }
    }

    @Test
    fun `upload rejects an empty file`() {
        val file = MockMultipartFile("file", "empty.jpg", "image/jpeg", ByteArray(0))
        assertThrows(ResponseStatusException::class.java) { mediaService.upload(file) }
    }
}
