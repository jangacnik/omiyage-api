package com.omiyage.backend.media

import com.omiyage.backend.config.MinioProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import java.net.URI

@Configuration
@EnableConfigurationProperties(MinioProperties::class)
class S3ClientConfig {

    @Bean
    fun s3Client(props: MinioProperties): S3Client = S3Client.builder()
        .endpointOverride(URI.create(props.endpoint))
        .region(Region.US_EAST_1)
        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(props.accessKey, props.secretKey)))
        .forcePathStyle(true)
        .build()
}
