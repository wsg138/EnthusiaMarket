package net.badgersmc.em.interaction.bedrock

import net.badgersmc.em.interaction.Menu
import org.bukkit.entity.Player
import org.geysermc.cumulus.form.Form
import org.geysermc.floodgate.api.FloodgateApi
import java.util.logging.Logger

/**
 * Base class for Bedrock Cumulus form menus.
 * Sends a Form via FloodgateApi and handles errors gracefully.
 */
abstract class BedrockMenuBase(
    protected val player: Player,
    protected val logger: Logger
) : Menu {

    abstract fun buildForm(): Form

    override fun open(player: Player) {
        try {
            val form = buildForm()
            sendForm(form)
            logger.fine("Opened ${this::class.simpleName} for ${player.name}")
        } catch (e: Exception) {
            logger.warning("Failed to open Bedrock menu for ${player.name}: ${e.message}")
            player.sendMessage("§cUnable to open menu. Please try again or use the Java interface.")
        }
    }

    /**
     * Sends the form via FloodgateApi. Open for testability.
     */
    protected open fun sendForm(form: Form) {
        FloodgateApi.getInstance().sendForm(player.uniqueId, form)
    }
}