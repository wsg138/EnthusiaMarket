package net.badgersmc.em.infrastructure.commands

import net.badgersmc.em.application.BreakDeleteMode
import net.badgersmc.em.application.ItemStackSerializer
import net.badgersmc.em.application.ShopManagementService
import net.badgersmc.em.application.ShopSearchService
import net.badgersmc.em.domain.shop.ShopRepository
import net.badgersmc.nexus.commands.annotations.Command
import net.badgersmc.nexus.commands.annotations.Context
import net.badgersmc.nexus.i18n.LangService
import net.badgersmc.nexus.paper.commands.annotations.Permission
import net.badgersmc.nexus.paper.commands.annotations.Subcommand
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/**
 * Player-facing `/shop` command (ItemShops parity sub-project 1). Menu-driven,
 * matching ItemShops: list / edit / trust / untrust / delete / breakdelete.
 */
@Command(name = "shop", description = "Manage your shops", aliases = ["shops"])
class ShopCommands(
    private val management: ShopManagementService,
    private val shopRepository: ShopRepository,
    private val breakDelete: BreakDeleteMode,
    private val search: ShopSearchService,
    private val lang: LangService,
) {
    @Subcommand("list")
    @Permission("enthusiamarket.shop.use")
    fun list(@Context sender: CommandSender) {
        val player = sender as? Player ?: run { sender.sendMessage(lang.msg("shop.cmd.players_only")); return }
        val shops = management.shopsOwnedBy(player.uniqueId)
        if (shops.isEmpty()) {
            player.sendMessage(lang.msg("shop.cmd.none_owned"))
            return
        }
        player.sendMessage(lang.msg("shop.cmd.list_header", "count" to shops.size))
        for (s in shops) {
            val sellName = ItemStackSerializer.deserialize(s.sellItem)?.type?.name?.lowercase() ?: "?"
            player.sendMessage(
                lang.msg(
                    "shop.cmd.list_line",
                    "world" to s.signWorld, "x" to s.signX, "y" to s.signY, "z" to s.signZ,
                    "sell_amt" to s.sellAmount, "sell" to sellName, "cost" to s.costAmount,
                )
            )
        }
    }

    @Subcommand("trust")
    @Permission("enthusiamarket.shop.use")
    fun trust(
        @Context sender: CommandSender,
        @net.badgersmc.nexus.commands.annotations.Arg("player") name: String,
        @net.badgersmc.nexus.commands.annotations.Arg("mode") mode: String = "menu",
    ) {
        val player = sender as? Player ?: run { sender.sendMessage(lang.msg("shop.cmd.players_only")); return }
        val target = org.bukkit.Bukkit.getOfflinePlayer(name)
        if (target.name == null && !target.hasPlayedBefore()) {
            player.sendMessage(lang.msg("shop.cmd.unknown_player", "name" to name)); return
        }
        if (mode.equals("all", ignoreCase = true)) {
            val n = management.trustAll(player.uniqueId, target.uniqueId)
            player.sendMessage(lang.msg("shop.cmd.trusted_all", "name" to name, "count" to n))
            return
        }
        net.badgersmc.em.interaction.gui.BulkTrustMenu(player.uniqueId, target.uniqueId, name, management, lang).open(player)
    }

    @Subcommand("untrust")
    @Permission("enthusiamarket.shop.use")
    fun untrust(
        @Context sender: CommandSender,
        @net.badgersmc.nexus.commands.annotations.Arg("player") name: String,
        // Accepted for parity (`/shop untrust <player> all`) but untrust is always
        // bulk in ItemShops, so the value is not branched on.
        @Suppress("UnusedParameter")
        @net.badgersmc.nexus.commands.annotations.Arg("mode") mode: String = "all",
    ) {
        val player = sender as? Player ?: run { sender.sendMessage(lang.msg("shop.cmd.players_only")); return }
        val target = org.bukkit.Bukkit.getOfflinePlayer(name)
        if (target.name == null && !target.hasPlayedBefore()) {
            player.sendMessage(lang.msg("shop.cmd.unknown_player", "name" to name)); return
        }
        val n = management.untrustAll(player.uniqueId, target.uniqueId)
        player.sendMessage(lang.msg("shop.cmd.untrusted_all", "name" to name, "count" to n))
    }

    @Subcommand("edit")
    @Permission("enthusiamarket.shop.use")
    fun edit(@Context sender: CommandSender) {
        val player = sender as? Player ?: run { sender.sendMessage(lang.msg("shop.cmd.players_only")); return }
        if (management.shopsOwnedBy(player.uniqueId).isEmpty()) {
            player.sendMessage(lang.msg("shop.cmd.none_owned")); return
        }
        net.badgersmc.em.interaction.gui.OwnedShopsMenu(player.uniqueId, shopRepository, management, lang).open(player)
    }

    @Subcommand("delete")
    @Permission("enthusiamarket.shop.use")
    fun delete(
        @Context sender: CommandSender,
        @net.badgersmc.nexus.commands.annotations.Arg("mode") mode: String = "menu",
    ) {
        val player = sender as? Player ?: run { sender.sendMessage(lang.msg("shop.cmd.players_only")); return }
        if (mode.equals("all", ignoreCase = true)) {
            if (!player.hasPermission("enthusiamarket.shop.delete.all")) {
                player.sendMessage(lang.msg("shop.cmd.no_permission")); return
            }
            val n = management.deleteAll(player.uniqueId)
            player.sendMessage(lang.msg("shop.cmd.deleted_all", "count" to n))
            return
        }
        if (management.shopsOwnedBy(player.uniqueId).isEmpty()) {
            player.sendMessage(lang.msg("shop.cmd.none_owned")); return
        }
        net.badgersmc.em.interaction.gui.DeleteShopsMenu(player.uniqueId, management, lang).open(player)
    }

    @Subcommand("breakdelete")
    @Permission("enthusiamarket.shop.use")
    fun breakDeleteCmd(
        @Context sender: CommandSender,
        @net.badgersmc.nexus.commands.annotations.Arg("mode") mode: String = "on",
    ) {
        val player = sender as? Player ?: run { sender.sendMessage(lang.msg("shop.cmd.players_only")); return }
        val durationMs = BreakDeleteMode.parseDurationMs(mode)
        if (durationMs == null) {
            breakDelete.disable(player.uniqueId)
            player.sendMessage(lang.msg("shop.cmd.breakdelete_off"))
            return
        }
        breakDelete.enable(player.uniqueId, durationMs)
        player.sendMessage(lang.msg("shop.cmd.breakdelete_on", "minutes" to (durationMs / 60_000)))
    }

    @Subcommand("search")
    @Permission("enthusiamarket.shop.use")
    fun search(
        @Context sender: CommandSender,
        @net.badgersmc.nexus.commands.annotations.Arg("query") query: String? = null,
        @net.badgersmc.nexus.commands.annotations.Arg("mode") modeArg: String = "any",
        @net.badgersmc.nexus.commands.annotations.Arg("page") pageArg: Int = 1,
    ) {
        val player = sender as? Player ?: run { sender.sendMessage(lang.msg("shop.cmd.players_only")); return }
        if (query == null) { player.sendMessage(lang.msg("shop.cmd.search.usage")); return }
        val material = org.bukkit.Material.matchMaterial(query)
        if (material == null) { player.sendMessage(lang.msg("shop.cmd.search.unknown_item", "query" to query)); return }
        val mode = when (modeArg.lowercase()) {
            "sell" -> ShopSearchService.SearchMode.SELL
            "buy" -> ShopSearchService.SearchMode.BUY
            else -> ShopSearchService.SearchMode.ANY
        }
        if (mode == ShopSearchService.SearchMode.BUY) {
            player.sendMessage(lang.msg("shop.cmd.search.buy_unavailable")); return
        }
        val results = search.search(material, mode, shopRepository.all()) { shop ->
            ItemStackSerializer.deserialize(shop.sellItem)?.type
        }
        if (results.isEmpty()) { player.sendMessage(lang.msg("shop.cmd.search.none", "query" to query)); return }
        net.badgersmc.em.interaction.gui.SearchResultsMenu(results, query, pageArg.coerceAtLeast(1), lang).open(player)
    }
}