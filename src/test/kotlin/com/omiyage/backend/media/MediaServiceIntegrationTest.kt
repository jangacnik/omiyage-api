package com.omiyage.backend.media

import com.omiyage.backend.PostgresIntegrationTest
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.MinIOContainer
import org.springframework.web.server.ResponseStatusException
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.CreateBucketRequest
import software.amazon.awssdk.services.s3.model.HeadBucketRequest
import software.amazon.awssdk.services.s3.model.NoSuchBucketException

@SpringBootTest
class MediaServiceIntegrationTest : PostgresIntegrationTest() {

    companion object {
        private val minio: MinIOContainer = MinIOContainer("minio/minio:RELEASE.2024-10-13T13-34-11Z")
            .withUserName("omiyage")
            .withPassword("omiyage123")
            .apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun minioProperties(registry: DynamicPropertyRegistry) {
            registry.add("omiyage.minio.endpoint") { minio.s3URL }
            registry.add("omiyage.minio.access-key") { "omiyage" }
            registry.add("omiyage.minio.secret-key") { "omiyage123" }
            registry.add("omiyage.minio.bucket") { "omiyage-test" }
        }
    }

    @Autowired
    lateinit var mediaService: MediaService

    @Autowired
    lateinit var s3Client: S3Client

    @BeforeEach
    fun ensureBucketExists() {
        try {
            s3Client.headBucket(HeadBucketRequest.builder().bucket("omiyage-test").build())
        } catch (e: NoSuchBucketException) {
            s3Client.createBucket(CreateBucketRequest.builder().bucket("omiyage-test").build())
        }
    }

    @Test
    fun `upload stores the file and returns a retrievable key`() {
        val file = MockMultipartFile("file", "photo.jpg", "image/jpeg", "fake-bytes".toByteArray())

        val key = mediaService.upload(file)

        assertTrue(key.startsWith("uploads/"))
        assertTrue(key.endsWith(".jpeg"))
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
