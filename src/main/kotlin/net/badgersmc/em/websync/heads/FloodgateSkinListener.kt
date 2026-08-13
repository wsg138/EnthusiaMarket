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
            capture(event)
        } catch (_: LinkageError) {
            diagnostics?.reject("floodgate_api")
            close()
        } catch (_: Exception) {
            diagnostics?.reject("listener")
        }
    }

    private fun capture(event: SkinApplyEvent) {
        val skin = event.newSkin() ?: return reject("skin_missing")
        val player = event.player()
        val playerId = player.javaUniqueId ?: return reject("player_missing")
        val property = skin.value() ?: return reject("property_missing")
        if (property.length > FloodgateTexturePropertyParser.MAX_ENCODED) {
            return reject("property_oversize")
        }
        val signature = skin.signature()?.take(FloodgateTexturePropertyParser.MAX_SIGNATURE)
        capture.capture(playerId, property, signature)
        player.correctUniqueId
            ?.takeIf { it != playerId }
            ?.let { capture.capture(it, property, signature) }
    }

    private fun reject(reason: String) {
        diagnostics?.reject(reason)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) runCatching { eventBus.unsubscribe(subscription) }
    }
}
