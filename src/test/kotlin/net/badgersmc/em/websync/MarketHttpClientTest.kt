package net.badgersmc.em.websync

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MarketHttpClientTest {
    @Test
    fun `player head upload signs raw PNG bytes and sends canonical player identity`() {
        val playerId = UUID.randomUUID()
        val png = byteArrayOf(1, 2, 3, 4)
        withServer { server ->
            server.createContext("/internal/v1/player-heads/${"a".repeat(64)}.png") { exchange ->
                val body = exchange.requestBody.readBytes()
                val timestamp = exchange.requestHeaders.getFirst("X-Enthusia-Timestamp")
                val eventId = exchange.requestHeaders.getFirst("X-Enthusia-Event-Id")
                val expected = MarketRequestSigner.sign(
                    "unit,test-secret", "PUT", exchange.requestURI.path, "enthusia-main", timestamp, eventId, body,
                )
                val valid = listOf(
                    exchange.requestMethod == "PUT",
                    exchange.requestHeaders.getFirst("Content-Type") == "image/png",
                    exchange.requestHeaders.getFirst("X-Enthusia-Player-Id") == playerId.toString(),
                    exchange.requestHeaders.getFirst("X-Enthusia-Signature") == expected,
                    body.contentEquals(png),
                ).all { it }
                val hash = "a".repeat(64)
                val response = """{"ok":true,"hash":"$hash","url":"https://market-api.enthusia.info/v1/player-heads/$hash.png","duplicate":false}""".toByteArray()
                exchange.sendResponseHeaders(if (valid) 200 else 400, if (valid) response.size.toLong() else -1)
                if (valid) exchange.responseBody.use { it.write(response) } else exchange.close()
            }
            server.start()
            assertEquals(
                DeliveryOutcome.Success,
                MarketHttpClient(config(server)).uploadPlayerHead(playerId, "a".repeat(64), png),
            )
        }
    }

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

    @Test
    fun `authenticated test uses explicit application user agent and verifies response body`() {
        val agents = mutableListOf<String>()
        withServer { server ->
            server.createContext("/internal/v1/test") { exchange ->
                agents += exchange.requestHeaders.getFirst("User-Agent") ?: ""
                val response = """{"ok":true,"authenticated":true}""".toByteArray()
                exchange.sendResponseHeaders(200, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
            server.start()
            val client = MarketHttpClient(config(server), "EnthusiaMarket/0.2.0")
            assertEquals(DeliveryOutcome.Success, client.authenticatedTest("epoch"))
            assertEquals(DeliveryOutcome.Success, client.authenticatedTest("epoch"))
            assertEquals(listOf("EnthusiaMarket/0.2.0", "EnthusiaMarket/0.2.0"), agents)
        }
    }

    @Test
    fun `HTTP success without authenticated confirmation is rejected safely`() {
        withServer { server ->
            server.createContext("/internal/v1/test") { exchange ->
                val response = """{"ok":true}""".toByteArray()
                exchange.sendResponseHeaders(200, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
            server.start()
            assertEquals(DeliveryOutcome.Pause("invalid_test_response"),
                MarketHttpClient(config(server)).authenticatedTest("epoch"))
        }
    }

    @Test
    fun `approved validation paths are normalized without response values`() {
        withServer { server ->
            server.createContext("/internal/v1/test") { exchange ->
                val response = """{"ok":false,"error":{"code":"invalid_request","diagnostic":{"category":"invalid_field","issues":[{"path":"stalls[45].stall.owner.uuid","value":"must-not-appear"}]}}}""".toByteArray()
                exchange.sendResponseHeaders(400, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
            server.start()
            assertEquals(DeliveryOutcome.Pause("invalid_field:stalls[].stall.owner.uuid"),
                MarketHttpClient(config(server)).authenticatedTest("epoch"))
        }
    }

    @Test
    fun `unapproved validation paths are never exposed`() {
        withServer { server ->
            server.createContext("/internal/v1/test") { exchange ->
                val response = """{"ok":false,"error":{"code":"invalid_request","diagnostic":{"category":"invalid_field","issues":[{"path":"privateOwnerName"}]}}}""".toByteArray()
                exchange.sendResponseHeaders(400, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
            server.start()
            assertEquals(DeliveryOutcome.Pause("bad_request"),
                MarketHttpClient(config(server)).authenticatedTest("epoch"))
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
