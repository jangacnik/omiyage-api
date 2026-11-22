package com.omiyage.backend.media

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.net.URI

/** S3-compatible store (Garage in the homelab). Path-style, and checksums only where S3 requires them, as Garage expects. */
@Component
@Profile("!dev & !test")
class S3ObjectStore(
    @Value("\${omiyage.media.s3.endpoint}") endpoint: String,
    @Value("\${omiyage.media.s3.region}") region: String,
    @Value("\${omiyage.media.s3.bucket}") private val bucket: String,
    @Value("\${omiyage.media.s3.access-key}") accessKey: String,
    @Value("\${omiyage.media.s3.secret-key}") secretKey: String,
) : ObjectStore {

    private val client = S3Client.builder()
        .endpointOverride(URI.create(endpoint))
        .region(Region.of(region))
        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
        .forcePathStyle(true)
        .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
        .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
        .build()

    override fun put(key: String, contentType: String, bytes: ByteArray) {
        val request = PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).build()
        client.putObject(request, RequestBody.fromBytes(bytes))
    }

    override fun get(key: String): ByteArray? =
        try {
            client.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build()).asByteArray()
        } catch (_: NoSuchKeyException) {
            null
        }

    override fun delete(key: String) {
        client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build())
    }
}
