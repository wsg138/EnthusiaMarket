package net.badgersmc.em.infrastructure.commands

import net.badgersmc.em.domain.shop.ShopRepository
import net.badgersmc.em.domain.stall.StallRepository
import net.badgersmc.em.domain.shop.ShopTransactionRepository
import net.badgersmc.em.interaction.gui.SearchResultsMenu
import net.badgersmc.nexus.commands.annotations.Command
import net.badgersmc.nexus.commands.annotations.Context
import net.badgersmc.nexus.i18n.LangService
import net.badgersmc.nexus.paper.commands.annotations.Permission
import net.badgersmc.nexus.paper.commands.annotations.Suggests
import net.badgersmc.nexus.commands.annotations.Arg
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/**
 * Quick alias: `/finditem <item>` → `/shop search <item>` with full tab completion.
 *
 * Placed in a standalone `@Command` class so Nexus registers `/finditem` as a
 * top-level command, not a `/shop` subcommand alias.  That way the first token
 * after `/finditem` is parsed as the [query] argument (with item-material
 * suggestions) instead of being interpreted as a subcommand name.
 */
@Command(name = "finditem", description = "Search shops for an item")
class FindItemCommand(
    private val shopRepository: ShopRepository,
    private val stallRepository: StallRepository,
    private val transactions: ShopTransactionRepository,
    private val lang: LangService,
) {
    @net.badgersmc.nexus.paper.commands.annotations.Subcommand("")
    @Permission("enthusiamarket.shop.use")
    fun find(
        @Context sender: CommandSender,
        @Arg("query")
        @Suggests("itemMaterials")
        query: String,
    ) {
        val player = sender as? Player ?: run { sender.sendMessage(lang.msg("shop.cmd.players_only")); return }
        val material = org.bukkit.Material.matchMaterial(query)
        // Exact match first, then prefix fallback for partial queries (2+ chars)
        if (material != null) {
            val results = shopRepository.findBySellMaterial(material.name)
                .sortedBy { it.costAmount.toDouble() / it.sellAmount.coerceAtLeast(1) }
            if (results.isNotEmpty()) {
                val ticker = net.badgersmc.em.application.PriceTickerService.compute(material.name, transactions)
                SearchResultsMenu(results, query, 1, lang, stallRepository, ticker).open(player)
                return
            }
        }
        // Prefix fallback
        if (query.length >= 2) {
            val prefixResults = shopRepository.findBySellMaterialPrefix(query.uppercase())
                .sortedBy { it.costAmount.toDouble() / it.sellAmount.coerceAtLeast(1) }
            if (prefixResults.isNotEmpty()) {
                SearchResultsMenu(prefixResults, query, 1, lang, stallRepository, null).open(player)
                return
            }
        }
        player.sendMessage(lang.msg("shop.cmd.search.none", "query" to query))
    }

}

