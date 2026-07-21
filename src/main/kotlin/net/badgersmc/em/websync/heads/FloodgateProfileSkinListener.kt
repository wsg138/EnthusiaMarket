package net.badgersmc.em.websync.heads

import com.destroystokyo.paper.profile.PlayerProfile
import org.bukkit.Bukkit
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.plugin.java.JavaPlugin
import org.geysermc.floodgate.api.FloodgateApi
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Captures the skin Floodgate has applied to Paper's player profile.
 *
 * Floodgate's SkinApplyEvent is emitted by the proxy in some deployments, so the backend must
 * also inspect the profile that arrives with the Bukkit join event. The short retries handle
 * servers where Floodgate applies that profile a few ticks after the player becomes visible.
 */
class FloodgateProfileSkinListener(
    private val plugin: JavaPlugin,
    private val capture: FloodgateSkinCaptureService,
    private val floodgatePlayer: (UUID) -> Boolean = { FloodgateApi.getInstance().isFloodgatePlayer(it) },
) : Listener, AutoCloseable {
    private val closed = AtomicBoolean()

    init {
        Bukkit.getPluginManager().registerEvent(
            PlayerJoinEvent::class.java,
            this,
            EventPriority.NORMAL,
            { _, event -> onJoin(event as PlayerJoinEvent) },
            plugin,
        )
    }

    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        if (!runCatching { floodgatePlayer(player.uniqueId) }.getOrDefault(false)) return
        capture.eventReceived()
        val captured = AtomicBoolean()
        listOf(20L, 60L, 120L).forEachIndexed { attempt, delay ->
            Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                if (closed.get() || captured.get() || !player.isOnline) return@Runnable
                if (captureProfileTexture(player) && captured.compareAndSet(false, true)) {
                    plugin.logger.info("Bedrock head profile capture queued for ${player.name}")
                } else if (attempt == 2) {
                    capture.reject("player_profile")
                    plugin.logger.warning("Bedrock head profile capture unavailable for ${player.name} (safe category: player_profile)")
                }
            }, delay)
        }
    }

    private fun captureProfileTexture(player: org.bukkit.entity.Player): Boolean {
        val profile = player.playerProfile
        val textureProperty = (profile as? PlayerProfile)
            ?.properties
            ?.firstOrNull { it.name == "textures" }
        if (textureProperty != null && FloodgateTexturePropertyParser.parse(textureProperty.value) != null) {
            capture.capture(player.uniqueId, textureProperty.value, textureProperty.signature)
            return true
        }

        val skinUrl = runCatching { profile.textures.skin?.toString() }.getOrNull() ?: return false
        return capture.captureProfileTexture(player.uniqueId, skinUrl)
    }

    override fun close() {
        closed.set(true)
        org.bukkit.event.HandlerList.unregisterAll(this)
    }
}
