package net.badgersmc.em.infrastructure.commands

import net.badgersmc.nexus.commands.annotations.Command
import net.badgersmc.nexus.commands.annotations.Context
import net.badgersmc.nexus.i18n.LangService
import net.badgersmc.nexus.paper.commands.annotations.Permission
import org.bukkit.command.CommandSender

/** Sends a multi-line shop tutorial from lang keys (IS2-15, REQ-301). Mirrors VaultCommands. */
@Command(name = "shophelp", description = "Shop tutorial", aliases = ["shoptutorial", "sht"])
class ShopHelpCommands(
    private val lang: LangService,
) {
    @net.badgersmc.nexus.paper.commands.annotations.Subcommand("show")
    @Permission("enthusiamarket.shop.help")
    fun show(@Context sender: CommandSender) {
        sender.sendMessage(lang.msg("shop.help.line1"))
        sender.sendMessage(lang.msg("shop.help.line2"))
        sender.sendMessage(lang.msg("shop.help.line3"))
        sender.sendMessage(lang.msg("shop.help.line4"))
        sender.sendMessage(lang.msg("shop.help.line5"))
    }
}