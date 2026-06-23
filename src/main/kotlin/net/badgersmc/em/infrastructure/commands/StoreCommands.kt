package net.badgersmc.em.infrastructure.commands

import net.badgersmc.em.config.EnthusiaMarketConfig
import net.badgersmc.nexus.commands.annotations.Command
import net.badgersmc.nexus.commands.annotations.Context
import net.badgersmc.nexus.i18n.LangService
import net.badgersmc.nexus.paper.commands.annotations.Permission
import org.bukkit.command.CommandSender

/** Sends the configured store URL (IS2-15, REQ-301). Mirrors VaultCommands. */
@Command(name = "store", description = "Open the server shop store", aliases = ["shopstore"])
class StoreCommands(
    private val config: EnthusiaMarketConfig,
    private val lang: LangService,
) {
    @net.badgersmc.nexus.paper.commands.annotations.Subcommand("show")
    @Permission("enthusiamarket.shop.store")
    fun show(@Context sender: CommandSender) {
        sender.sendMessage(lang.msg("shop.store.url", "url" to config.store.url))
    }
}