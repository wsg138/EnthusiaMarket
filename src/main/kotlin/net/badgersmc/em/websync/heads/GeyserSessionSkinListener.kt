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
            captureSkin(event)
        } catch (_: LinkageError) {
            // An incompatible optional Geyser API must not affect Market runtime behavior.
        } catch (_: Exception) {
            // Invalid event data keeps the generic Bedrock fallback.
        }
    }

    private fun captureSkin(event: SessionSkinApplyEvent) {
        if (!event.bedrock()) return
        val skinData = event.skinData()
        if (!standardGeometry(skinData.geometry())) return
        val skin = skinData.skin()
        if (skin.failed() || skin.skinData().isEmpty()) return
        // The event-owned array may be reused; copy before returning from the callback.
        capture.capture(event.uuid(), skin.skinData().copyOf())
    }

    private fun standardGeometry(geometry: SkinGeometry): Boolean =
        geometry.geometryData().isBlank() &&
            (geometry.geometryName() == SkinGeometry.WIDE.geometryName() ||
                geometry.geometryName() == SkinGeometry.SLIM.geometryName())

    override fun close() {
        eventBus.unregisterAll(this)
    }
}
