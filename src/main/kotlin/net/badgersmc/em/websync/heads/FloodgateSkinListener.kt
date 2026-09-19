package net.badgersmc.em.websync.heads

import org.geysermc.floodgate.api.FloodgateApi
import org.geysermc.floodgate.api.event.FloodgateEventBus
import org.geysermc.floodgate.api.event.FloodgateSubscriber
import org.geysermc.floodgate.api.event.skin.SkinApplyEvent
import java.util.UUID
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
            captureSkin(event)
        } catch (_: LinkageError) {
            reject("floodgate_api")
            close()
        } catch (_: Exception) {
            reject("listener")
        }
    }

    private fun captureSkin(event: SkinApplyEvent) {
        val skin = event.newSkin() ?: return reject("skin_missing")
        val player = event.player()
        val playerId = player.javaUniqueId ?: return reject("player_missing")
        val value = propertyValue(skin.value()) ?: return
        val signature = skin.signature()?.take(FloodgateTexturePropertyParser.MAX_SIGNATURE)
        capture.capture(playerId, value, signature)
        captureCorrectedIdentity(playerId, player.correctUniqueId, value, signature)
    }

    private fun propertyValue(property: String?): String? {
        val value = property ?: return rejectProperty("property_missing")
        return value.takeIf { it.length <= FloodgateTexturePropertyParser.MAX_ENCODED }
            ?: rejectProperty("property_oversize")
    }

    private fun rejectProperty(category: String): String? {
        reject(category)
        return null
    }

    private fun captureCorrectedIdentity(playerId: UUID, correctedPlayerId: UUID?, value: String, signature: String?) {
        correctedPlayerId?.takeIf { it != playerId }?.let { capture.capture(it, value, signature) }
    }

    private fun reject(category: String) {
        diagnostics?.reject(category)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) runCatching { eventBus.unsubscribe(subscription) }
    }
}
