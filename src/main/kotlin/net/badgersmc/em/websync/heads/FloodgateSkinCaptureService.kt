package net.badgersmc.em.websync.heads

import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

data class FloodgateCaptureStatus(
    val events: Long,
    val accepted: Long,
    val queued: Long,
    val rejected: Long,
    val failed: Long,
    val dropped: Long,
    val lastFailure: String?,
)

interface FloodgateCaptureDiagnostics {
    fun eventReceived()
    fun reject(category: String)
}

/** Moves Floodgate event data off the event thread and persists only the finished head PNG. */
class FloodgateSkinCaptureService(
    private val store: BedrockHeadStore,
    private val fetcher: SkinTextureFetcher = DefaultSkinTextureFetcher,
) : FloodgateTextureCapture, FloodgateCaptureDiagnostics, AutoCloseable {
    private val events = AtomicLong()
    private val accepted = AtomicLong()
    private val queued = AtomicLong()
    private val rejected = AtomicLong()
    private val failed = AtomicLong()
    private val dropped = AtomicLong()
    @Volatile private var lastFailure: String? = null
    private val executor = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(16),
        { task -> Thread(task, "EnthusiaMarket-FloodgateHeads").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardPolicy(),
    )

    override fun eventReceived() { events.incrementAndGet() }

    override fun reject(category: String) {
        rejected.incrementAndGet()
        lastFailure = category
    }

    override fun capture(playerId: UUID, value: String, signature: String?) {
        val texture = FloodgateTexturePropertyParser.parse(value) ?: return reject("texture_property")
        capture(playerId, texture)
    }

    /** Captures a validated Mojang skin texture supplied by Paper's online player profile. */
    fun captureProfileTexture(playerId: UUID, skinUrl: String): Boolean {
        val texture = FloodgateTexturePropertyParser.parseUrl(skinUrl) ?: return false
        capture(playerId, texture)
        return true
    }

    private fun capture(playerId: UUID, texture: MojangTexture) {
        accepted.incrementAndGet()
        queueCapture(playerId, texture)
    }

    private fun queueCapture(playerId: UUID, texture: MojangTexture) {
        try {
            executor.execute {
                try {
                    val png = BedrockHeadRenderer.render(fetcher.fetch(texture))
                    queued.incrementAndGet()
                    store.captureRendered(playerId, png)
                } catch (_: java.io.IOException) {
                    fail("texture_fetch")
                } catch (_: IllegalArgumentException) {
                    fail("texture_render")
                } catch (_: Exception) {
                    fail("capture")
                }
            }
        } catch (_: RejectedExecutionException) {
            dropped.incrementAndGet()
            lastFailure = "queue_full"
        }
    }

    fun status() = FloodgateCaptureStatus(events.get(), accepted.get(), queued.get(), rejected.get(), failed.get(), dropped.get(), lastFailure)

    private fun fail(category: String) {
        failed.incrementAndGet()
        lastFailure = category
    }

    override fun close() { executor.shutdownNow() }
}
