package net.badgersmc.em.infrastructure.commands

import net.badgersmc.em.application.BreakDeleteMode
import net.badgersmc.em.application.AdminBreakMode
import net.badgersmc.em.application.ItemStackSerializer
import net.badgersmc.em.application.LookAtShopResolver
import net.badgersmc.em.application.ShopManagementService
import net.badgersmc.em.application.ShopSignRenderer
import net.badgersmc.em.application.ShopVaultService
import net.badgersmc.em.domain.shop.ShopRepository
import net.badgersmc.em.domain.shop.ShopTransactionRepository
import net.badgersmc.em.domain.stall.StallRepository
import net.badgersmc.nexus.commands.annotations.Arg
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
@Suppress("LongParameterList")
class ShopCommands(
    private val management: ShopManagementService,
    private val shopRepository: ShopRepository,
    private val breakDelete: BreakDeleteMode,
    private val transactions: ShopTransactionRepository,
    private val lookAt: LookAtShopResolver,
    private val adminBreak: AdminBreakMode,
    private val signRenderer: ShopSignRenderer,
    private val lang: LangService,
    private val vaultService: ShopVaultService,
    private val stallRepository: StallRepository,
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
        @net.badgersmc.nexus.commands.annotations.Arg("query")
        @net.badgersmc.nexus.paper.commands.annotations.Suggests("itemMaterials")
        query: String,
    ) {
        val player = sender as? Player ?: run { sender.sendMessage(lang.msg("shop.cmd.players_only")); return }
        val material = org.bukkit.Material.matchMaterial(query)
        // Exact match first, then prefix fallback for partial queries (2+ chars)
        if (material != null) {
            val results = shopRepository.findBySellMaterial(material.name)
                .sortedBy { it.costAmount.toDouble() / it.sellAmount.coerceAtLeast(1) } // cheapest unit price first
            if (results.isNotEmpty()) {
                val ticker = net.badgersmc.em.application.PriceTickerService.compute(material.name, transactions)
                net.badgersmc.em.interaction.gui.SearchResultsMenu(
                    results, query, 1, lang, stallRepository, ticker,
                ).open(player)
                return
            }
        }
        // Prefix fallback
        if (query.length >= 2) {
            val prefixResults = shopRepository.findBySellMaterialPrefix(query.uppercase())
                .sortedBy { it.costAmount.toDouble() / it.sellAmount.coerceAtLeast(1) }
            if (prefixResults.isNotEmpty()) {
                // Ticker is meaningless for prefix searches (heterogeneous items)
                net.badgersmc.em.interaction.gui.SearchResultsMenu(
                    prefixResults, query, 1, lang, stallRepository, null,
                ).open(player)
                return
            }
        }
        player.sendMessage(lang.msg("shop.cmd.search.none", "query" to query))
    }

    @Subcommand("history")
    @Permission("enthusiamarket.shop.use")
    fun history(@Context sender: CommandSender, @Arg("page") page: Int = 1) {
        val player = sender as? Player ?: run { sender.sendMessage(lang.msg("shop.cmd.players_only")); return }
        val safePage = page.coerceIn(1, 1000)
        val offset = (safePage - 1) * PAGE_SIZE
        // Fetch one extra row to detect if there's a next page
        val rows = transactions.findByOwnerOrBuyer(player.uniqueId, PAGE_SIZE + 1, offset)
        if (rows.isEmpty()) { player.sendMessage(lang.msg("shop.history.empty")); return }
        val hasNext = rows.size > PAGE_SIZE
        val displayRows = if (hasNext) rows.dropLast(1) else rows
        player.sendMessage(lang.msg("shop.history.header", "page" to safePage))
        val fmt = java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm")
            .withZone(java.time.ZoneId.systemDefault())
        for (t in displayRows) {
            if (t.owner == player.uniqueId) {
                val buyerName = org.bukkit.Bukkit.getOfflinePlayer(t.buyer).name ?: "Unknown"
                player.sendMessage(lang.msg(
                    "shop.history.sold",
                    "when" to fmt.format(java.time.Instant.ofEpochMilli(t.createdAt)),
                    "qty" to t.quantity, "item" to t.item, "price" to t.totalPrice, "buyer" to buyerName,
                ))
            } else {
                val sellerName = org.bukkit.Bukkit.getOfflinePlayer(t.owner).name ?: "Unknown"
                player.sendMessage(lang.msg(
                    "shop.history.bought",
                    "when" to fmt.format(java.time.Instant.ofEpochMilli(t.createdAt)),
                    "qty" to t.quantity, "item" to t.item, "price" to t.totalPrice, "seller" to sellerName,
                ))
            }
        }
        if (hasNext) {
            player.sendMessage(lang.msg("shop.history.more", "page" to (safePage + 1)))
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
        SignRenderHelper.renderToSign(signRenderer, sign, shop)
    }

    @Subcommand("admin fixall")
    @Permission("enthusiamarket.admin.shop")
    fun adminFixAll(@Context sender: CommandSender) {
        val player = sender as? Player ?: run { sender.sendMessage(lang.msg("shop.cmd.players_only")); return }
        val shops = shopRepository.all()
        var fixed = 0; var skipped = 0; var errors = 0
        for (shop in shops) {
            val world = org.bukkit.Bukkit.getWorld(shop.signWorld)
            if (world == null) { errors++; continue }
            val sign = world.getBlockAt(shop.signX, shop.signY, shop.signZ).state as? org.bukkit.block.Sign
            if (sign == null) { skipped++; continue }
            reRenderShopSign(shop, sign)
            fixed++
        }
        player.sendMessage(lang.msg("shop.admin.fixall.result", "fixed" to fixed, "skipped" to skipped, "errors" to errors))
    }

    @Subcommand("admin breakothers")
    @Permission("enthusiamarket.admin.shop")
    fun adminBreakOthers(@Context sender: CommandSender) {
        val player = sender as? Player ?: run { sender.sendMessage(lang.msg("shop.cmd.players_only")); return }
        adminBreak.enable(player.uniqueId, 5L * 60_000) // default 5-minute duration
        player.sendMessage(lang.msg("shop.admin.breakothers.enabled", "minutes" to 5))
    }

    @Subcommand("admin vault")
    @Permission("enthusiamarket.admin.shop")
    fun adminVault(
        @Context sender: CommandSender,
        @net.badgersmc.nexus.commands.annotations.Arg("player") name: String,
    ) {
        val player = sender as? Player ?: run { sender.sendMessage(lang.msg("shop.cmd.players_only")); return }
        val target = org.bukkit.Bukkit.getOfflinePlayer(name)
        if (target.name == null && !target.hasPlayedBefore()) {
            player.sendMessage(lang.msg("shop.cmd.unknown_player", "name" to name)); return
        }
        val displayName = target.name ?: name
        net.badgersmc.em.interaction.gui.VaultAdminMenu(
            target.uniqueId, displayName, vaultService, lang
        ).open(player)
    }

    @Subcommand("admin contents")
    @Permission("enthusiamarket.admin.shop")
    fun adminContents(@Context sender: CommandSender) {
        val player = sender as? Player ?: run { sender.sendMessage(lang.msg("shop.cmd.players_only")); return }
        val shop = lookAtShop(player) ?: run { player.sendMessage(lang.msg("shop.admin.no_target")); return }
        net.badgersmc.em.interaction.gui.ShopContentsMenu(shop, lang).open(player)
    }

    companion object {
        private const val PAGE_SIZE = 10
    }
}
