package net.badgersmc.em.websync.heads

import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin

fun interface FloodgateTextureCapture { fun capture(playerId: java.util.UUID, value: String, signature: String?) }

/** Loads Floodgate-linked code only when the optional backend plugin is present. */
object FloodgateHeadIntegration {
    @Suppress("TooGenericExceptionCaught")
    fun start(plugin: JavaPlugin, capture: FloodgateTextureCapture): AutoCloseable? {
        if (!Bukkit.getPluginManager().isPluginEnabled("floodgate")) return null
        return try {
            Class.forName("net.badgersmc.em.websync.heads.FloodgateSkinListener", true, plugin.javaClass.classLoader)
                .getConstructor(FloodgateTextureCapture::class.java).newInstance(capture) as AutoCloseable
        } catch (_: LinkageError) {
            plugin.logger.warning("Bedrock head capture is unavailable (safe category: floodgate_api)")
            null
        } catch (_: Exception) {
            plugin.logger.warning("Bedrock head capture is unavailable (safe category: floodgate_registration)")
            null
        }
    }
}
