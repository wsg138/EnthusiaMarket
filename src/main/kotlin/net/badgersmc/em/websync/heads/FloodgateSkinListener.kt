package net.badgersmc.em.websync.heads

import org.geysermc.floodgate.api.FloodgateApi
import org.geysermc.floodgate.api.event.FloodgateEventBus
import org.geysermc.floodgate.api.event.FloodgateSubscriber
import org.geysermc.floodgate.api.event.skin.SkinApplyEvent
import java.util.concurrent.atomic.AtomicBoolean

/** Optional Floodgate backend listener; it never modifies or cancels SkinApplyEvent. */
class FloodgateSkinListener @JvmOverloads constructor(
    private val capture: FloodgateTextureCapture,
    private val eventBus: FloodgateEventBus = FloodgateApi.getInstance().eventBus,
) : AutoCloseable {
    private val closed = AtomicBoolean()
    private val subscription: FloodgateSubscriber<SkinApplyEvent> = eventBus.subscribe(SkinApplyEvent::class.java, ::onSkin)

    private fun onSkin(event: SkinApplyEvent) {
        if (closed.get()) return
        try {
            val skin = event.newSkin() ?: return
            val playerId = event.player().correctUniqueId ?: return
            val value = skin.value()?.takeIf { it.length <= FloodgateTexturePropertyParser.MAX_ENCODED } ?: return
            val signature = skin.signature()?.take(FloodgateTexturePropertyParser.MAX_SIGNATURE)
            capture.capture(playerId, value, signature)
        } catch (_: LinkageError) {
            close()
        } catch (_: Exception) {
            // Invalid optional data must never affect Floodgate login.
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) runCatching { eventBus.unsubscribe(subscription) }
    }
}
