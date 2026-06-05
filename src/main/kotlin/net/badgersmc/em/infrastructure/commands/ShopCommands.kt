package net.badgersmc.em.infrastructure.commands

import net.badgersmc.em.application.BreakDeleteMode
import net.badgersmc.em.application.AdminBreakMode
import net.badgersmc.em.application.ItemStackSerializer
import net.badgersmc.em.application.LookAtShopResolver
import net.badgersmc.em.application.ShopManagementService
import net.badgersmc.em.application.ShopSearchService
import net.badgersmc.em.application.ShopSignRenderer
import net.badgersmc.em.domain.shop.ShopRepository
import net.badgersmc.em.domain.shop.ShopTransactionRepository
import net.badgersmc.em.domain.shop.SignDirection
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
    private val transactions: ShopTransactionRepository,
    private val lookAt: LookAtShopResolver,
    private val adminBreak: AdminBreakMode,
    private val signRenderer: ShopSignRenderer,
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

    @Subcommand("history")
    @Permission("enthusiamarket.shop.use")
    fun history(
        @Context sender: CommandSender,
        @net.badgersmc.nexus.commands.annotations.Arg("page") page: Int = 1,
    ) {
        val player = sender as? Player ?: run { sender.sendMessage(lang.msg("shop.cmd.players_only")); return }
        val p = page.coerceAtLeast(1)
        val rows = transactions.findByOwner(player.uniqueId, PAGE_SIZE, (p - 1) * PAGE_SIZE)
        if (rows.isEmpty()) { player.sendMessage(lang.msg("shop.history.empty")); return }
        player.sendMessage(lang.msg("shop.history.header", "page" to p))
        val fmt = java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm")
            .withZone(java.time.ZoneId.systemDefault())
        for (t in rows) {
            val buyer = org.bukkit.Bukkit.getOfflinePlayer(t.buyer).name ?: "Unknown"
            player.sendMessage(lang.msg(
                "shop.history.line",
                "when" to fmt.format(java.time.Instant.ofEpochMilli(t.createdAt)),
                "qty" to t.quantity, "item" to t.item, "price" to t.totalPrice, "buyer" to buyer,
            ))
        }
    }

    private fun lookAtShop(player: Player): net.badgersmc.em.domain.shop.Shop? {
        val b = player.getTargetBlockExact(6) ?: return null
        return lookAt.resolve(b.world.name, b.x, b.y, b.z)
    }

    @Subcommand("admin view")
    @Permission("enthusiamarket.admin.shop")
    fun adminView(@Context sender: CommandSender) {
        val player = sender as? Player ?: run { sender.sendMessage(lang.msg("shop.cmd.players_only")); return }
        val shop = lookAtShop(player) ?: run { player.sendMessage(lang.msg("shop.admin.no_target")); return }
        net.badgersmc.em.interaction.gui.ShopEditMenu(shop, shopRepository, management, lang).open(player)
    }

    @Subcommand("admin info")
    @Permission("enthusiamarket.admin.shop")
    fun adminInfo(@Context sender: CommandSender) {
        val player = sender as? Player ?: run { sender.sendMessage(lang.msg("shop.cmd.players_only")); return }
        val shop = lookAtShop(player) ?: run { player.sendMessage(lang.msg("shop.admin.no_target")); return }
        val owner = org.bukkit.Bukkit.getOfflinePlayer(shop.owner).name ?: "Unknown"
        val sell = ItemStackSerializer.deserialize(shop.sellItem)?.type?.name?.lowercase() ?: "?"
        player.sendMessage(lang.msg("shop.admin.info.header", "owner" to owner))
        player.sendMessage(lang.msg("shop.admin.info.where",
            "world" to shop.signWorld, "x" to shop.signX, "y" to shop.signY, "z" to shop.signZ,
            "cworld" to shop.containerWorld, "cx" to shop.containerX, "cy" to shop.containerY, "cz" to shop.containerZ))
        player.sendMessage(lang.msg("shop.admin.info.trade",
            "dir" to shop.direction.name, "sell_amt" to shop.sellAmount, "sell" to sell, "cost" to shop.costAmount))
        player.sendMessage(lang.msg("shop.admin.info.flags",
            "trusted" to shop.trusted.size, "frozen" to shop.frozen, "searchable" to shop.searchEnabled))
    }

    @Subcommand("admin remove")
    @Permission("enthusiamarket.admin.shop")
    fun adminRemove(@Context sender: CommandSender) {
        val player = sender as? Player ?: run { sender.sendMessage(lang.msg("shop.cmd.players_only")); return }
        val shop = lookAtShop(player) ?: run { player.sendMessage(lang.msg("shop.admin.no_target")); return }
        if (management.adminDelete(shop.id)) player.sendMessage(lang.msg("shop.admin.remove.done"))
        else player.sendMessage(lang.msg("shop.admin.remove.not_found"))
    }

    @Subcommand("admin fix")
    @Permission("enthusiamarket.admin.shop")
    fun adminFix(@Context sender: CommandSender) {
        val player = sender as? Player ?: run { sender.sendMessage(lang.msg("shop.cmd.players_only")); return }
        val shop = lookAtShop(player) ?: run { player.sendMessage(lang.msg("shop.admin.no_target")); return }
        val signState = org.bukkit.Bukkit.getWorld(shop.signWorld)
            ?.getBlockAt(shop.signX, shop.signY, shop.signZ)?.state
        if (signState !is org.bukkit.block.Sign) { player.sendMessage(lang.msg("shop.admin.fix.not_a_sign")); return }
        reRenderShopSign(shop, signState)
        val containerState = org.bukkit.Bukkit.getWorld(shop.containerWorld)
            ?.getBlockAt(shop.containerX, shop.containerY, shop.containerZ)?.state
        if (containerState !is org.bukkit.block.Container) player.sendMessage(lang.msg("shop.admin.fix.container_missing"))
        else player.sendMessage(lang.msg("shop.admin.fix.done"))
    }

    /** Re-apply the four sign lines from stored shop data onto the live sign block. */
    private fun reRenderShopSign(shop: net.badgersmc.em.domain.shop.Shop, sign: org.bukkit.block.Sign) {
        val sell = ItemStackSerializer.deserialize(shop.sellItem)?.type?.name?.lowercase() ?: "?"
        val costDisplay = if (shop.direction == SignDirection.TRADE) {
            val costMat = ItemStackSerializer.deserialize(shop.costItem)?.type?.name?.lowercase() ?: "?"
            "${shop.costAmount}x $costMat"
        } else {
            "${shop.costAmount}"
        }
        val side = sign.getSide(org.bukkit.block.sign.Side.FRONT)
        signRenderer.lines(shop.direction, sell, shop.sellAmount, costDisplay)
            .forEachIndexed { i, c -> side.line(i, c) }
        sign.update()
    }

    @Subcommand("admin breakothers")
    @Permission("enthusiamarket.admin.shop")
    fun adminBreakOthers(
        @Context sender: CommandSender,
        @net.badgersmc.nexus.commands.annotations.Arg("mode") mode: String = "on",
    ) {
        val player = sender as? Player ?: run { sender.sendMessage(lang.msg("shop.cmd.players_only")); return }
        val durationMs = BreakDeleteMode.parseDurationMs(mode)
        if (durationMs == null) {
            adminBreak.disable(player.uniqueId)
            player.sendMessage(lang.msg("shop.admin.breakothers.disabled"))
            return
        }
        adminBreak.enable(player.uniqueId, durationMs)
        player.sendMessage(lang.msg("shop.admin.breakothers.enabled", "minutes" to (durationMs / 60_000)))
    }

    companion object {
        private const val PAGE_SIZE = 10
    }
}
