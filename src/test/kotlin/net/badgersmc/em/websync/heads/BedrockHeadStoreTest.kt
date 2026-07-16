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
import java.util.concurrent.atomic.AtomicBoolean
import com.google.gson.JsonParser
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
    fun `retry processes each due hash once and synchronizes every alias`() {
        val calls = AtomicInteger()
        var now = 0L
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        val store = store(upload = { calls.incrementAndGet(); DeliveryOutcome.Retry() }, clock = { now })
        store.capture(first, validSkin())
        await { calls.get() == 1 }
        store.capture(second, validSkin())
        await { store.status().pending == 2 }

        now = 10_000L
        store.retryPending()
        await { calls.get() == 2 }
        assertEquals(2, calls.get())
        assertEquals(2, pendingSchedules().size)
        assertEquals(1, pendingSchedules().values.toSet().size)
        store.close()
    }

    @Test
    fun `successful grouped delivery publishes every alias`() {
        val calls = AtomicInteger()
        var now = 0L
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        val store = store(upload = {
            if (calls.incrementAndGet() == 1) DeliveryOutcome.Retry() else DeliveryOutcome.Success
        }, clock = { now })
        store.capture(first, validSkin())
        await { calls.get() == 1 }
        store.capture(second, validSkin())
        await { store.status().pending == 2 }

        now = 10_000L
        store.retryPending()
        await { store.url(first) != null && store.url(second) != null }
        assertEquals(2, calls.get())
        assertEquals(0, store.status().pending)
        store.close()
    }

    @Test
    fun `different hashes receive separate grouped uploads`() {
        val calls = AtomicInteger()
        val store = store(upload = { calls.incrementAndGet(); DeliveryOutcome.Retry() })
        store.capture(UUID.randomUUID(), validSkin())
        await { calls.get() == 1 }
        store.capture(UUID.randomUUID(), validSkin(red = 21.toByte()))
        await { calls.get() == 2 }
        assertEquals(2, calls.get())
        store.close()
    }

    @Test
    fun `completion leaves an alias changed during delivery pending`() {
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val uploads = AtomicInteger()
        val store = store(upload = {
            if (uploads.incrementAndGet() == 1) {
                started.countDown()
                release.await()
                DeliveryOutcome.Success
            } else {
                DeliveryOutcome.Retry()
            }
        })
        store.capture(first, validSkin())
        assertTrue(started.await(2, java.util.concurrent.TimeUnit.SECONDS))
        store.capture(second, validSkin(red = 21.toByte()))
        release.countDown()
        await { store.url(first) != null }
        await { store.status().pending == 1 }
        assertNull(store.url(second))
        store.close()
    }

    @Test
    fun `corrupt pending hash removes every alias without invoking uploader`() {
        val calls = AtomicInteger()
        var now = 0L
        val store = store(upload = { calls.incrementAndGet(); DeliveryOutcome.Retry() }, clock = { now })
        store.capture(UUID.randomUUID(), validSkin())
        await { calls.get() == 1 }
        store.capture(UUID.randomUUID(), validSkin())
        await { store.status().pending == 2 }
        File(directory, "website-heads/pending").listFiles()!!.single().writeBytes(byteArrayOf(1))

        now = 10_000L
        store.retryPending()
        await { store.status().pending == 0 }
        assertEquals(1, calls.get())
        assertTrue(File(directory, "website-heads/pending").listFiles().orEmpty().isEmpty())
        store.close()

        val reloaded = store(upload = { DeliveryOutcome.Success })
        assertEquals(0, reloaded.status().pending)
        reloaded.close()
    }

    @Test
    fun `transient pending read failure defers every alias and preserves file`() {
        val calls = AtomicInteger()
        val ioFailure = AtomicBoolean(false)
        var now = 0L
        val reader = PendingFileReader { file, hash ->
            if (ioFailure.get()) PendingFileRead.IoFailure
            else PendingFileRead.Valid(file.readBytes())
        }
        val store = store(upload = { calls.incrementAndGet(); DeliveryOutcome.Retry() }, clock = { now }, reader = reader)
        store.capture(UUID.randomUUID(), validSkin())
        await { calls.get() == 1 }
        store.capture(UUID.randomUUID(), validSkin())
        await { store.status().pending == 2 }
        ioFailure.set(true)

        now = 10_000L
        store.retryPending()
        await { store.status().lastError == "pending_file_io" }
        assertEquals(2, store.status().pending)
        assertEquals(1, pendingSchedules().values.toSet().size)
        assertEquals(1, File(directory, "website-heads/pending").listFiles().orEmpty().size)
        assertEquals(1, calls.get())
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
        reader: PendingFileReader = defaultPendingFileReader,
    ) = BedrockHeadStore(
        directory,
        { config() },
        { _, _, _, _ -> upload() },
        published,
        clock,
        reader,
    )

    private fun config() = WebsiteSyncConfig(
        true, URI("https://market-api.enthusia.info"), "enthusia-main", "synthetic-secret",
        Duration.ZERO, Duration.ofMillis(250), Duration.ofSeconds(2), Duration.ofMinutes(15),
        Duration.ofSeconds(5), Duration.ofSeconds(10), 1, Duration.ofSeconds(5), Duration.ofMinutes(5), false, false,
    )

    private fun validSkin(red: Byte = 20) = ByteArray(64 * 64 * 4).also { bytes ->
        for (y in 8 until 16) for (x in 8 until 16) {
            val offset = (y * 64 + x) * 4
            bytes[offset] = red
            bytes[offset + 1] = 40
            bytes[offset + 2] = 60
            bytes[offset + 3] = 0xff.toByte()
        }
    }

    private fun pendingSchedules(): Map<String, String> {
        val pending = JsonParser.parseString(File(directory, "website-heads/index.json").readText())
            .asJsonObject.getAsJsonObject("pending")
        return pending.entrySet().associate { (id, value) ->
            id to "${value.asJsonObject.get("attempts").asInt}:${value.asJsonObject.get("nextAttemptAt").asLong}"
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
