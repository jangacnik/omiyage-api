package com.omiyage.backend.config

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.net.InetSocketAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private const val SCRAPE_CONTENT_TYPE = "text/plain; version=0.0.4; charset=utf-8"

/**
 * Serves the Prometheus scrape on its own port, only when `omiyage.metrics.port` is set (env OMIYAGE_METRICS_PORT).
 * The port is not in the Kubernetes Service, so metrics stay inside the cluster, and the public port keeps no
 * metrics endpoint. 0 picks a free port (tests).
 */
@Component
@ConditionalOnProperty("omiyage.metrics.port")
class PrometheusMetricsServer(
    private val registry: PrometheusMeterRegistry,
    @Value("\${omiyage.metrics.port}") private val requestedPort: Int,
) {

    private val log = LoggerFactory.getLogger("startup")
    private var server: HttpServer? = null
    private var executor: ExecutorService? = null

    val port: Int get() = server?.address?.port ?: -1

    @PostConstruct
    fun start() {
        val started = HttpServer.create(InetSocketAddress(requestedPort), 0)
        started.createContext("/metrics") { exchange -> respond(exchange) }
        val threads = Executors.newSingleThreadExecutor { task -> Thread(task, "metrics-server").apply { isDaemon = true } }
        started.executor = threads
        started.start()
        server = started
        executor = threads
        log.info("metrics_server_started port={}", port)
    }

    @PreDestroy
    fun stop() {
        server?.stop(0)
        executor?.shutdown()
    }

    val isStopped: Boolean get() = executor?.isShutdown ?: true

    private fun respond(exchange: HttpExchange) {
        exchange.use {
            // HttpServer contexts match by path prefix, so /metricsx would reach this handler too.
            if (it.requestURI.path != "/metrics") {
                it.sendResponseHeaders(404, -1)
                return
            }
            if (it.requestMethod != "GET") {
                it.sendResponseHeaders(405, -1)
                return
            }
            val body = registry.scrape().toByteArray()
            it.responseHeaders.add("Content-Type", SCRAPE_CONTENT_TYPE)
            it.sendResponseHeaders(200, body.size.toLong())
            it.responseBody.use { out -> out.write(body) }
        }
    }
}
