@file:Suppress("FunctionNaming", "MagicNumber")

package net.badgersmc.em.websync.heads

import net.badgersmc.em.websync.DeliveryOutcome
import net.badgersmc.em.websync.WebsiteSyncConfig
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.URI
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BedrockHeadStoreTest {
    @TempDir lateinit var directory: File

    @Test
    fun `successful capture publishes first-party URL and removes pending file`() {
        val published = mutableListOf<UUID>()
        val player = UUID.randomUUID()
        val store = store(upload = { DeliveryOutcome.Success }, published = published::add)
        store.capture(player, validSkin())

        await { store.url(player) != null }
        assertTrue(store.url(player)!!.matches(Regex("https://market-api\\.enthusia\\.info/v1/player-heads/[0-9a-f]{64}\\.png")))
        assertEquals(listOf(player), published)
        assertEquals(0, store.status().pending)
        assertTrue(File(directory, "website-heads/pending").listFiles().orEmpty().isEmpty())
        store.close()
    }

    @Test
    fun `failed upload survives restart and retry publishes it`() {
        val player = UUID.randomUUID()
        val first = store(upload = { DeliveryOutcome.Retry() })
        first.capture(player, validSkin())
        await { first.status().pending == 1 }
        assertNull(first.url(player))
        first.close()

        var now = System.currentTimeMillis() + 1_000_000
        val second = store(upload = { DeliveryOutcome.Success }, clock = { now })
        assertEquals(1, second.status().pending)
        second.retryPending()
        await { second.url(player) != null }
        assertNotNull(second.url(player))
        second.close()
    }

    @Test
    fun `identical pending heads share one content-addressed file`() {
        val calls = AtomicInteger()
        val store = store(upload = { calls.incrementAndGet(); DeliveryOutcome.Retry() })
        store.capture(UUID.randomUUID(), validSkin())
        store.capture(UUID.randomUUID(), validSkin())
        await { store.status().pending == 2 && calls.get() == 1 }
        assertEquals(1, calls.get())
        assertEquals(1, File(directory, "website-heads/pending").listFiles().orEmpty().count { it.extension == "png" })
        store.close()
    }

    @Test
    fun `invalid skin never enters the durable pending cache`() {
        val store = store(upload = { DeliveryOutcome.Success })
        store.capture(UUID.randomUUID(), ByteArray(23))
        await { store.status().lastError == "invalid_skin" }
        assertEquals(0, store.status().pending)
        store.close()
    }

    private fun store(
        upload: () -> DeliveryOutcome,
        published: (UUID) -> Unit = {},
        clock: () -> Long = System::currentTimeMillis,
    ) = BedrockHeadStore(
        directory,
        { config() },
        { _, _, _, _ -> upload() },
        published,
        clock,
    )

    private fun config() = WebsiteSyncConfig(
        true, URI("https://market-api.enthusia.info"), "enthusia-main", "synthetic-secret",
        Duration.ZERO, Duration.ofMillis(250), Duration.ofSeconds(2), Duration.ofMinutes(15),
        Duration.ofSeconds(5), Duration.ofSeconds(10), 1, Duration.ofSeconds(5), Duration.ofMinutes(5), false, false,
    )

    private fun validSkin() = ByteArray(64 * 64 * 4).also { bytes ->
        for (y in 8 until 16) for (x in 8 until 16) {
            val offset = (y * 64 + x) * 4
            bytes[offset] = 20
            bytes[offset + 1] = 40
            bytes[offset + 2] = 60
            bytes[offset + 3] = 0xff.toByte()
        }
    }

    private fun await(condition: () -> Boolean) {
        repeat(200) {
            if (condition()) return
            Thread.sleep(10)
        }
        error("condition was not met")
    }
}
