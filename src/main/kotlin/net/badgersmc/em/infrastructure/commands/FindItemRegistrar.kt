package net.badgersmc.em.infrastructure.commands

import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import com.mojang.brigadier.tree.LiteralCommandNode
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import net.badgersmc.em.application.MaterialSuggestions
import net.badgersmc.em.application.PriceTickerService
import net.badgersmc.em.domain.shop.Shop
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

            val itemProvider = com.mojang.brigadier.suggestion.SuggestionProvider<CommandSourceStack> { _, builder ->
                MaterialSuggestions.matching(itemNames, builder.remaining)
                    .forEach(builder::suggest)
                builder.buildFuture()
            }

            val node: LiteralCommandNode<CommandSourceStack> = Commands.literal("finditem")
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
                                val material = Material.matchMaterial(query)
                                val results: List<Shop>
                                if (material != null) {
                                    results = shopRepo.findBySellMaterial(material.name)
                                        .sortedBy { it.costAmount.toDouble() / it.sellAmount.coerceAtLeast(1) }
                                } else if (query.length >= 2) {
                                    results = shopRepo.findBySellMaterialPrefix(query.uppercase())
                                        .sortedBy { it.costAmount.toDouble() / it.sellAmount.coerceAtLeast(1) }
                                } else {
                                    player.sendMessage(lang.msg("shop.cmd.search.unknown_item", "query" to query))
                                    return@runTaskAsynchronously
                                }
                                if (results.isEmpty()) {
                                    player.sendMessage(lang.msg("shop.cmd.search.none", "query" to query))
                                    return@runTaskAsynchronously
                                }
                                val ticker = PriceTickerService.compute(
                                    material?.name ?: query.uppercase(), transactions
                                )
                                // GUI must open on the server thread
                                Bukkit.getScheduler().runTask(plugin) { _ ->
                                    SearchResultsMenu(results, query, 1, lang, stallRepo, ticker).open(player)
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
