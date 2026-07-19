package net.badgersmc.em.infrastructure.commands

import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import com.mojang.brigadier.tree.LiteralCommandNode
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import net.badgersmc.em.application.MaterialSuggestions
import net.badgersmc.em.application.PriceTickerService
import net.badgersmc.em.application.ShopSearchService
import net.badgersmc.em.application.ItemStackSerializer
import net.badgersmc.em.domain.shop.ShopRepository
import net.badgersmc.em.domain.shop.ShopTransactionRepository
import net.badgersmc.em.domain.stall.StallRepository
import net.badgersmc.em.interaction.gui.SearchResultsMenu
import net.badgersmc.nexus.i18n.LangService
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin

/**
 * Registers `/finditem <query>` as a top-level Paper Brigadier command with
 * item-material tab completion.  Bypasses Nexus because its @Subcommand
 * annotation does not support empty subcommand names.
 */
object FindItemRegistrar {

    fun register(plugin: Plugin, itemNames: List<String>, nexus: net.badgersmc.nexus.core.NexusContext) {
        plugin.lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            val shopRepo = nexus.getBean(ShopRepository::class)
            val transactions = nexus.getBean(ShopTransactionRepository::class)
            val stallRepo = nexus.getBean(StallRepository::class)
            val lang = nexus.getBean(LangService::class)
            val searchService = nexus.getBean(ShopSearchService::class)

            val itemProvider = com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> { _, builder ->
                MaterialSuggestions.matching(itemNames, builder.remaining)
                    .forEach(builder::suggest)
                builder.buildFuture()
            }

            val node: LiteralCommandNode<CommandSourceStack> = Commands.literal("finditem")
                .requires { src -> src.sender.hasPermission("enthusiamarket.shop.use") }
                .then(
                    RequiredArgumentBuilder.argument<CommandSourceStack, String>("query", StringArgumentType.word())
                        .suggests(itemProvider)
                        .executes { ctx ->
                            val query = StringArgumentType.getString(ctx, "query")
                            val sender = ctx.source.sender
                            val player = sender as? Player ?: run {
                                sender.sendRichMessage("<red>Players only")
                                return@executes 0
                            }
                            // Offload DB queries off the main thread; open the GUI
                            // back on the server thread (REQ-605 sync requirement).
                            Bukkit.getScheduler().runTaskAsynchronously(plugin) { _ ->
                                if (query.length < 2) {
                                    Bukkit.getScheduler().runTask(plugin) { _ ->
                                        player.sendMessage(lang.msg("shop.cmd.search.unknown_item", "query" to query))
                                    }
                                    return@runTaskAsynchronously
                                }
                                // Nested container metadata is inspected on the server thread.
                                val candidates = shopRepo.all().filter { it.searchEnabled }
                                val ticker = Material.matchMaterial(query)?.let {
                                    PriceTickerService.compute(it.name, transactions)
                                }
                                Bukkit.getScheduler().runTask(plugin) { _ ->
                                    val results = candidates.mapNotNull { shop ->
                                        val soldItem = ItemStackSerializer.deserialize(shop.sellItem) ?: return@mapNotNull null
                                        searchService.findMatch(shop.searchEnabled, soldItem, query)?.let {
                                            SearchResultsMenu.Result(shop, it.material, it.nested)
                                        }
                                    }
                                    if (results.isEmpty()) {
                                        player.sendMessage(lang.msg("shop.cmd.search.none", "query" to query))
                                    } else {
                                        SearchResultsMenu(results, query, lang, stallRepo, ticker).open(player)
                                    }
                                }
                            }
                            1
                        }
                )
                .build()

            event.registrar().register(node, "Search for items in the market")
        }
    }
}
