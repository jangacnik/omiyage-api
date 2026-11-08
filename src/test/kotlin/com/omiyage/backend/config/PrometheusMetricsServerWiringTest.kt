package com.omiyage.backend.config

import com.omiyage.backend.PostgresIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/** The real context starts the metrics server when the port is set and injects Boot's PrometheusMeterRegistry. */
@SpringBootTest(properties = ["omiyage.metrics.port=0"])
class PrometheusMetricsServerWiringTest : PostgresIntegrationTest() {

    @Autowired
    lateinit var server: PrometheusMetricsServer

    @Test
    fun `scrapes the application registry on the metrics port`() {
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:${server.port}/metrics")).GET().build()

        val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())

        assertEquals(200, response.statusCode())
        assertTrue(response.body().contains("# TYPE "), response.body().take(500))
    }
}
