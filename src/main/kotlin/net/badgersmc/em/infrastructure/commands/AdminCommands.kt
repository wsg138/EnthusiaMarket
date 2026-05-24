package net.badgersmc.em.infrastructure.commands

import net.badgersmc.em.application.ImportStallsService
import net.badgersmc.em.config.EnthusiaMarketConfig
import net.badgersmc.em.domain.stall.StallRepository
import net.badgersmc.nexus.commands.annotations.Command
import net.badgersmc.nexus.commands.annotations.Context
import net.badgersmc.nexus.paper.commands.annotations.Permission
import net.badgersmc.nexus.paper.commands.annotations.Subcommand
import org.bukkit.command.CommandSender

@Command(name = "em", description = "EnthusiaMarket administrative commands", aliases = ["enthusiamarket"])
class AdminCommands(
    private val service: ImportStallsService,
    private val stalls: StallRepository,
    private val config: EnthusiaMarketConfig
) {
    @Subcommand("import")
    @Permission("enthusiamarket.admin.import")
    fun import(@Context sender: CommandSender) {
        val r = service.import(config.market.world, config.market.regionPrefix)
        sender.sendMessage("[EnthusiaMarket] import: created=${r.created} skipped=${r.skipped}")
    }

    @Subcommand("list")
    @Permission("enthusiamarket.admin.list")
    fun list(@Context sender: CommandSender) {
        for (s in stalls.all()) {
            sender.sendMessage("  ${s.id} [${s.state}] region=${s.world}:${s.regionId}")
        }
    }
}