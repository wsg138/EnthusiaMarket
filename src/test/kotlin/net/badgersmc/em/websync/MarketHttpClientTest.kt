package net.badgersmc.em.websync

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MarketHttpClientTest {
    @Test
    fun `redirects are never followed and signature is sent`() {
        val followed = AtomicInteger()
        val signatureSeen = mutableListOf<String>()
        withServer { server ->
            server.createContext("/internal/v1/test") { exchange ->
                signatureSeen += exchange.requestHeaders.getFirst("X-Enthusia-Signature") ?: ""
                exchange.responseHeaders.add("Location", "/followed")
                exchange.sendResponseHeaders(307, -1)
                exchange.close()
            }
            server.createContext("/followed") { exchange -> followed.incrementAndGet(); exchange.sendResponseHeaders(200, -1); exchange.close() }
            server.start()
            val outcome = MarketHttpClient(config(server)).authenticatedTest("epoch")
            assertIs<DeliveryOutcome.Pause>(outcome)
            assertEquals(0, followed.get())
            assertTrue(signatureSeen.single().matches(Regex("v1=[0-9a-f]{64}")))
        }
    }

    @Test
    fun `stall not found requests full reconciliation`() {
        withServer { server ->
            server.createContext("/internal/v1/test") { exchange ->
                val response = """{"ok":false,"error":{"code":"stall_not_found","message":"missing"}}""".toByteArray()
                exchange.sendResponseHeaders(409, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
            server.start()
            val outcome = MarketHttpClient(config(server)).authenticatedTest("epoch")
            assertEquals(DeliveryOutcome.Reconcile("stall_not_found"), outcome)
        }
    }

    private fun withServer(block: (HttpServer) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        try { block(server) } finally { server.stop(0) }
    }

    private fun config(server: HttpServer) = WebsiteSyncConfig(
        false, URI("http://127.0.0.1:${server.address.port}"), "enthusia-main", "unit,test-secret",
        Duration.ZERO, Duration.ofMillis(250), Duration.ofSeconds(2), Duration.ofMinutes(15),
        Duration.ofSeconds(2), Duration.ofSeconds(2), 1, Duration.ofSeconds(1), Duration.ofSeconds(2),
        false, false,
    )
}
