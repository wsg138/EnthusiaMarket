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
    private val diagnostics = capture as? FloodgateCaptureDiagnostics
    private val subscription: FloodgateSubscriber<SkinApplyEvent> = eventBus.subscribe(SkinApplyEvent::class.java, ::onSkin)

    private fun onSkin(event: SkinApplyEvent) {
        if (closed.get()) return
        diagnostics?.eventReceived()
        try {
            val skin = event.newSkin() ?: return diagnostics?.reject("skin_missing") ?: Unit
            val player = event.player()
            val playerId = player.javaUniqueId ?: return diagnostics?.reject("player_missing") ?: Unit
            val property = skin.value() ?: return diagnostics?.reject("property_missing") ?: Unit
            val value = property.takeIf { it.length <= FloodgateTexturePropertyParser.MAX_ENCODED }
                ?: return diagnostics?.reject("property_oversize") ?: Unit
            val signature = skin.signature()?.take(FloodgateTexturePropertyParser.MAX_SIGNATURE)
            capture.capture(playerId, value, signature)
            player.correctUniqueId?.takeIf { it != playerId }?.let { capture.capture(it, value, signature) }
        } catch (_: LinkageError) {
            diagnostics?.reject("floodgate_api")
            close()
        } catch (_: Exception) {
            diagnostics?.reject("listener")
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) runCatching { eventBus.unsubscribe(subscription) }
    }
}
