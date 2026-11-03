package com.omiyage.backend.order

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class ReceivedWebhook(val timestamp: String?, val signature: String?, val body: ByteArray)

/** A real local HTTP endpoint that records every request and answers with scripted statuses. */
class WebhookReceiver : AutoCloseable {
    private val received = LinkedBlockingQueue<ReceivedWebhook>()
    private val statuses = LinkedBlockingQueue<Int>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/events") { exchange ->
            received.add(
                ReceivedWebhook(
                    exchange.requestHeaders.getFirst(TIMESTAMP_HEADER),
                    exchange.requestHeaders.getFirst(SIGNATURE_HEADER),
                    exchange.requestBody.readAllBytes(),
                ),
            )
            exchange.sendResponseHeaders(statuses.poll() ?: 204, -1)
            exchange.close()
        }
        start()
    }

    val url = "http://127.0.0.1:${server.address.port}/events"

    fun respondWith(vararg codes: Int) = codes.forEach { statuses.add(it) }

    fun next(): ReceivedWebhook = requireNotNull(received.poll(10, TimeUnit.SECONDS)) { "no webhook arrived" }

    fun nothingWithin(millis: Long): Boolean = received.poll(millis, TimeUnit.MILLISECONDS) == null

    override fun close() = server.stop(0)
}
