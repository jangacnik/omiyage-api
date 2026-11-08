package com.omiyage.backend.config

import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

class PrometheusMetricsServerTest {

    private val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
    private val server = PrometheusMetricsServer(registry, 0)
    private val client = HttpClient.newHttpClient()

    @BeforeEach
    fun start() = server.start()

    @AfterEach
    fun stop() = server.stop()

    private fun send(method: String, path: String): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:${server.port}$path"))
            .method(method, HttpRequest.BodyPublishers.noBody())
            .build()
        return client.send(request, HttpResponse.BodyHandlers.ofString())
    }

    @Test
    fun `serves the registry in the prometheus text format`() {
        registry.counter("omiyage_test_events").increment(3.0)

        val response = send("GET", "/metrics")

        assertEquals(200, response.statusCode())
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("text/plain"))
        assertTrue(response.body().contains("omiyage_test_events_total 3.0"), response.body())
    }

    @Test
    fun `only answers GET`() {
        assertEquals(405, send("POST", "/metrics").statusCode())
    }

    @Test
    fun `serves nothing else`() {
        assertEquals(404, send("GET", "/actuator/env").statusCode())
        assertEquals(404, send("GET", "/").statusCode())
        assertEquals(404, send("GET", "/metricsx").statusCode())
        assertEquals(404, send("GET", "/metrics/x").statusCode())
    }

    @Test
    fun `stop shuts down the request thread`() {
        server.stop()

        assertTrue(server.isStopped)
    }
}
