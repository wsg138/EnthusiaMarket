package net.badgersmc.em.infrastructure.bedrock

import net.badgersmc.nexus.i18n.LangService
import net.badgersmc.nexus.annotations.Component
import org.bukkit.entity.Player
import java.util.UUID

/**
 * Dispatches UI interaction for Bedrock (Floodgate) vs Java players.
 *
 * For Bedrock players, Cumulus forms are dispatched instead of Bukkit GUI.
 * Uses reflection to check FloodgateApi to avoid hard runtime dependency
 * when Floodgate is not installed on the server.
 */
@Component
open class UiDispatcher(
    private val lang: LangService
) {

    /**
     * Returns true if the given UUID belongs to a Bedrock/Floodgate player.
     * Uses reflection to access FloodgateApi so the plugin works gracefully
     * when Floodgate is absent.
     * Open for testability.
     */
    open fun isBedrockPlayer(uuid: UUID): Boolean {
        return try {
            val floodgateApiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi")
            val getInstance = floodgateApiClass.getMethod("getInstance")
            val floodgateApi = getInstance.invoke(null)
            val getPlayer = floodgateApiClass.getMethod("getPlayer", UUID::class.java)
            getPlayer.invoke(floodgateApi, uuid) != null
        } catch (_: Throwable) {
            // Floodgate not installed or reflection failed — assume Java player
            false
        }
    }

    /**
     * Dispatch the appropriate UI for the player type.
     *
     * - Bedrock (Floodgate) players: Cumulus form
     * - Java players: Bukkit inventory GUI (TODO: post TDD-43)
     */
    fun dispatch(player: Player) {
        if (isBedrockPlayer(player.uniqueId)) {
            sendCumulusForm(player)
        } else {
            // TODO: Java — send Bukkit inventory GUI
        }
    }

    /**
     * Send a Cumulus SimpleForm to a Bedrock player via FloodgateApi.
     *
     * Uses reflection to avoid hard runtime dependency on Cumulus/Floodgate.
     * Falls back to a chat message if the libraries are not available at runtime.
     */
    private fun sendCumulusForm(player: Player) {
        try {
            val floodgateApiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi")
            val getInstance = floodgateApiClass.getMethod("getInstance")
            val floodgateApi = getInstance.invoke(null)

            // Build form using Cumulus SimpleForm builder via reflection
            val formBuilderClass = Class.forName("org.geysermc.cumulus.form.SimpleForm")
            val builderMethod = formBuilderClass.getMethod("builder")
            val builder = builderMethod.invoke(null)

            val builderClass = builder.javaClass
            builderClass.getMethod("title", String::class.java).invoke(builder, "Stall Menu")
            builderClass.getMethod("content", String::class.java).invoke(builder, "Manage your stall")
            builderClass.getMethod("button", String::class.java).invoke(builder, "View Details")
            builderClass.getMethod("button", String::class.java).invoke(builder, "Manage Shop Signs")

            val form = builderClass.getMethod("build").invoke(builder)
            floodgateApi::class.java.getMethod("sendForm", UUID::class.java, form.javaClass)
                .invoke(floodgateApi, player.uniqueId, form)
        } catch (e: Throwable) {
            // Cumulus or Floodgate not available at runtime — log and skip
            player.sendMessage(lang.msg("bedrock.unavailable"))
        }
    }
}