package net.badgersmc.em.infrastructure.commands

import co.aikar.commands.BaseCommand
import co.aikar.commands.annotation.CommandAlias
import co.aikar.commands.annotation.CommandPermission
import co.aikar.commands.annotation.Subcommand
import net.badgersmc.em.application.ImportStallsService
import net.badgersmc.em.domain.stall.StallRepository
import org.bukkit.command.CommandSender

@CommandAlias("enthusiamarket|em")
@CommandPermission("enthusiamarket.admin")
class AdminCommands(
    private val service: ImportStallsService,
    private val stalls: StallRepository,
    private val world: String,
    private val prefix: String
) : BaseCommand() {

    @Subcommand("import")
    fun import(sender: CommandSender) = runImport(sender)

    @Subcommand("list")
    fun list(sender: CommandSender) = runList(sender)

    fun runImport(sender: CommandSender) {
        val r = service.import(world, prefix)
        sender.sendMessage("[EnthusiaMarket] import: created=${r.created} skipped=${r.skipped}")
    }

    fun runList(sender: CommandSender) {
        for (s in stalls.all()) {
            sender.sendMessage("  ${s.id} [${s.state}] region=${s.world}:${s.regionId}")
        }
    }
}
