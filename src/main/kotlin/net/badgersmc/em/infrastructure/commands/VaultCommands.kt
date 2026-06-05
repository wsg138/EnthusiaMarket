package net.badgersmc.em.infrastructure.commands

import net.badgersmc.em.application.ShopVaultService
import net.badgersmc.em.interaction.gui.ShopVaultMenu
import net.badgersmc.nexus.commands.annotations.Command
import net.badgersmc.nexus.commands.annotations.Context
import net.badgersmc.nexus.paper.commands.annotations.Subcommand
import net.badgersmc.nexus.i18n.LangService
import net.badgersmc.nexus.paper.commands.annotations.Permission
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

@Command(name = "shopvault", description = "Withdraw barter payments", aliases = ["svault"])
class VaultCommands(
    private val vaultService: ShopVaultService,
    private val lang: LangService,
) {
    @Subcommand("open")
    @Permission("enthusiamarket.shop.vault")
    fun open(@Context sender: CommandSender) {
        val player = sender as? Player ?: run { sender.sendMessage(lang.msg("shop.cmd.players_only")); return }
        ShopVaultMenu(player, vaultService, 1, lang).open(player)
    }
}
