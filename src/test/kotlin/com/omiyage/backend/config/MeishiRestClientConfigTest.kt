package com.omiyage.backend.config

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress

class MeishiRestClientConfigTest {

    private lateinit var server: HttpServer

    @BeforeEach
    fun startServer() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/api/ping") { exchange ->
            val body = "pong".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
    }

    @AfterEach
    fun stopServer() = server.stop(0)

    @Test
    fun `client resolves relative uris against the configured api url`() {
        val apiUrl = "http://127.0.0.1:${server.address.port}"
        val client = MeishiRestClientConfig().meishiRestClient(MeishiProperties(jwtSecret = "s", apiUrl = apiUrl))

        val response = client.get().uri("/api/ping").retrieve().body(String::class.java)

        assertEquals("pong", response)
    }
}
