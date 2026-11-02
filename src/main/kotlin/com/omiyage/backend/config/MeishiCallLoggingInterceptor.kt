package com.omiyage.backend.config

import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.http.HttpRequest
import org.springframework.http.client.ClientHttpRequestExecution
import org.springframework.http.client.ClientHttpRequestInterceptor
import org.springframework.http.client.ClientHttpResponse

private const val REQUEST_ID_HEADER = "X-Request-Id"

/** Forwards the request id to MeishiApi and logs one line per call: method, path (never query/headers/bodies), status, duration. */
class MeishiCallLoggingInterceptor : ClientHttpRequestInterceptor {

    private val log = LoggerFactory.getLogger("meishi.client")

    override fun intercept(request: HttpRequest, body: ByteArray, execution: ClientHttpRequestExecution): ClientHttpResponse {
        MDC.get("requestId")?.let { request.headers.set(REQUEST_ID_HEADER, it) }
        val startNanos = System.nanoTime()
        try {
            val response = execution.execute(request, body)
            log.info("meishi_call method={} path={} status={} durationMs={}", request.method, request.uri.path, response.statusCode.value(), elapsedMs(startNanos))
            return response
        } catch (e: Exception) {
            log.warn("meishi_call_failed method={} path={} error={} durationMs={}", request.method, request.uri.path, e.javaClass.simpleName, elapsedMs(startNanos))
            throw e
        }
    }

    private fun elapsedMs(startNanos: Long) = (System.nanoTime() - startNanos) / 1_000_000
}
