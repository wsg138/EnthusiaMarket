package net.badgersmc.em.websync

import java.net.URI
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame

class HeadUploadClientCacheTest {
    @Test
    fun `reuses client until configuration changes or cache is cleared`() {
        val created = AtomicInteger()
        val cache = HeadUploadClientCache { config ->
            created.incrementAndGet()
            MarketHttpClient(config)
        }
        val firstConfig = config()

        val first = cache.client(firstConfig)
        assertEquals(first, cache.client(firstConfig))
        val replacement = cache.client(config(serverId = "other-server"))
        assertNotSame(first, replacement)
        cache.clear()
        assertNotSame(replacement, cache.client(firstConfig))

        assertEquals(3, created.get())
    }

    private fun config(serverId: String = "enthusia-main") = WebsiteSyncConfig(
        true, URI("https://market-api.enthusia.info"), serverId, "synthetic-secret",
        Duration.ZERO, Duration.ofMillis(250), Duration.ofSeconds(2), Duration.ofMinutes(15),
        Duration.ofSeconds(5), Duration.ofSeconds(10), 1, Duration.ofSeconds(5), Duration.ofMinutes(5), false, false,
    )
}
