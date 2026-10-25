package com.omiyage.backend.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.net.http.HttpClient
import java.time.Duration

private val CONNECT_TIMEOUT = Duration.ofSeconds(2)
private val READ_TIMEOUT = Duration.ofSeconds(5)

@Configuration
class MeishiRestClientConfig {

    @Bean
    fun meishiRestClient(props: MeishiProperties): RestClient {
        val requestFactory = JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build())
        requestFactory.setReadTimeout(READ_TIMEOUT)
        return RestClient.builder().baseUrl(props.apiUrl).requestFactory(requestFactory).build()
    }
}
