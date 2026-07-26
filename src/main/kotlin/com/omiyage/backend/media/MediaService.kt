package com.omiyage.backend.media

import com.omiyage.backend.config.MinioProperties
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.util.UUID

private val ALLOWED_CONTENT_TYPES = setOf("image/jpeg", "image/png", "image/webp", "image/gif")

@Service
class MediaService(private val s3Client: S3Client, private val props: MinioProperties) {

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

        s3Client.putObject(
            PutObjectRequest.builder()
                .bucket(props.bucket)
                .key(key)
                .contentType(contentType)
                .build(),
            RequestBody.fromInputStream(file.inputStream, file.size),
        )

        return key
    }
}
