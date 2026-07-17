package net.badgersmc.em.websync.heads

import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Moves Floodgate event data off the event thread and persists only the finished head PNG. */
class FloodgateSkinCaptureService(
    private val store: BedrockHeadStore,
    private val fetcher: SkinTextureFetcher = DefaultSkinTextureFetcher,
) : FloodgateTextureCapture, AutoCloseable {
    private val executor = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(16),
        { task -> Thread(task, "EnthusiaMarket-FloodgateHeads").apply { isDaemon = true } },
        ThreadPoolExecutor.DiscardPolicy(),
    )

    override fun capture(playerId: UUID, value: String, signature: String?) {
        val texture = FloodgateTexturePropertyParser.parse(value) ?: return
        executor.execute {
            runCatching { BedrockHeadRenderer.render(fetcher.fetch(texture)) }
                .onSuccess { store.captureRendered(playerId, it) }
        }
    }

    override fun close() { executor.shutdownNow() }
}
