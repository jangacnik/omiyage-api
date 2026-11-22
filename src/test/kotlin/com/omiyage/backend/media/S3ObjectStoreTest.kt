package com.omiyage.backend.media

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import software.amazon.awssdk.core.ResponseBytes
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectResponse
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest

class S3ObjectStoreTest {

    private val client = mockk<S3Client>(relaxed = true)
    private val store = S3ObjectStore(client, "omiyage-media")
    private val bytes = byteArrayOf(1, 2, 3)

    @Test
    fun `put writes the object with its content type to the bucket`() {
        val request = slot<PutObjectRequest>()
        every { client.putObject(capture(request), any<RequestBody>()) } returns mockk()

        store.put("uploads/a.jpeg", "image/jpeg", bytes)

        assertEquals("omiyage-media", request.captured.bucket())
        assertEquals("uploads/a.jpeg", request.captured.key())
        assertEquals("image/jpeg", request.captured.contentType())
    }

    @Test
    fun `get returns the object's bytes`() {
        every { client.getObjectAsBytes(any<GetObjectRequest>()) } returns
            ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), bytes)

        assertArrayEquals(bytes, store.get("uploads/a.jpeg"))
    }

    @Test
    fun `get returns null for a missing object`() {
        every { client.getObjectAsBytes(any<GetObjectRequest>()) } throws NoSuchKeyException.builder().build()

        assertNull(store.get("uploads/missing.jpeg"))
    }

    @Test
    fun `delete removes the object from the bucket`() {
        store.delete("uploads/a.jpeg")

        verify { client.deleteObject(DeleteObjectRequest.builder().bucket("omiyage-media").key("uploads/a.jpeg").build()) }
    }

    @Test
    fun `config builds an S3 store without contacting the server`() {
        val built = S3ObjectStoreConfig().objectStore("http://127.0.0.1:3900", "garage", "omiyage-media", "key", "secret")

        assertTrue(built is S3ObjectStore)
    }
}
