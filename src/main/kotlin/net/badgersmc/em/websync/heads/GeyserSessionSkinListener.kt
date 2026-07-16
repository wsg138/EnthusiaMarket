package net.badgersmc.em.websync.heads

import org.geysermc.geyser.api.GeyserApi
import org.geysermc.geyser.api.event.EventRegistrar
import org.geysermc.geyser.api.event.bedrock.SessionSkinApplyEvent
import org.geysermc.geyser.api.skin.SkinGeometry

/** The only class linked to Geyser. Never load this class when Geyser is absent. */
class GeyserSessionSkinListener(private val capture: BedrockSkinCapture) : EventRegistrar, AutoCloseable {
    private val eventBus = GeyserApi.api().eventBus()

    init {
        eventBus.register(this, this)
        eventBus.subscribe(this, SessionSkinApplyEvent::class.java, ::onSkin)
    }

    private fun onSkin(event: SessionSkinApplyEvent) {
        try {
            if (!event.bedrock()) return
            val skinData = event.skinData()
            val geometry = skinData.geometry()
            if (geometry.geometryData().isNotBlank() ||
                geometry.geometryName() != SkinGeometry.WIDE.geometryName() &&
                geometry.geometryName() != SkinGeometry.SLIM.geometryName()) return
            val skin = skinData.skin()
            if (skin.failed()) return
            if (skin.skinData().isEmpty()) return
            // The event-owned array may be reused; copy before returning from the callback.
            capture.capture(event.uuid(), skin.skinData().copyOf())
        } catch (_: LinkageError) {
            // An incompatible optional Geyser API must not affect Market runtime behavior.
        } catch (_: Exception) {
            // Invalid event data keeps the generic Bedrock fallback.
        }
    }

    override fun close() {
        eventBus.unregisterAll(this)
    }
}
