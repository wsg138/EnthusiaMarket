package net.badgersmc.em.websync.heads

import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID

fun interface BedrockSkinCapture {
    fun capture(playerId: UUID, skin: ByteArray)
}

/** Loads the Geyser-linked listener only when Geyser-Spigot is actually available. */
object GeyserHeadIntegration {
    @Suppress("TooGenericExceptionCaught")
    fun start(plugin: JavaPlugin, capture: BedrockSkinCapture): AutoCloseable? {
        if (!Bukkit.getPluginManager().isPluginEnabled("Geyser-Spigot")) return null
        return try {
            val type = Class.forName(
                "net.badgersmc.em.websync.heads.GeyserSessionSkinListener",
                true,
                plugin.javaClass.classLoader,
            )
            type.getConstructor(BedrockSkinCapture::class.java).newInstance(capture) as AutoCloseable
        } catch (_: LinkageError) {
            plugin.logger.warning("Bedrock head capture is unavailable (safe category: geyser_api)")
            null
        } catch (_: Exception) {
            plugin.logger.warning("Bedrock head capture is unavailable (safe category: geyser_registration)")
            null
        }
    }
}
