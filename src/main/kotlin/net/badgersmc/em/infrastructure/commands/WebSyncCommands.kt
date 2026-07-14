package net.badgersmc.em.infrastructure.commands

import net.badgersmc.em.websync.WebsiteSyncService
import net.badgersmc.nexus.commands.annotations.Arg
import net.badgersmc.nexus.commands.annotations.Command
import net.badgersmc.nexus.commands.annotations.Context
import net.badgersmc.nexus.paper.commands.annotations.Permission
import net.badgersmc.nexus.paper.commands.annotations.Subcommand
import org.bukkit.command.CommandSender
import java.time.Instant

@Command(name = "em", description = "EnthusiaMarket administrative commands", aliases = ["enthusiamarket"])
class WebSyncCommands(private val service: WebsiteSyncService) {
    @Subcommand("websync status")
    @Permission(PERMISSION)
    fun status(@Context sender: CommandSender) {
        val status = service.status()
        sender.sendMessage("Website sync: configured=${yesNo(status.configuredEnabled)}, active=${yesNo(status.active)}, " +
            "secret=${yesNo(status.secretConfigured)}, validation=${status.validation}")
        sender.sendMessage("Pending stalls=${status.pendingStalls}, full=${yesNo(status.pendingFull)}, " +
            "oldest=${age(status.oldestPendingAgeMillis)}, snapshot=${status.snapshotRevision}")
        sender.sendMessage("Last full=${instant(status.lastFullSuccess)}, last stall=${instant(status.lastStallSuccess)}, " +
            "status=${status.errorCategory ?: "ok"}")
    }

    @Subcommand("websync secret")
    @Permission(PERMISSION)
    fun secret(@Context sender: CommandSender, @Arg("value") value: String) {
        val result = service.setSecret(value)
        sender.sendMessage(if (result.config != null) "Website sync secret stored; synchronization remains in its configured state."
            else "Website sync configuration is invalid; synchronization is disabled.")
    }

    @Subcommand("websync clear-secret")
    @Permission(PERMISSION)
    fun clearSecret(@Context sender: CommandSender, @Arg("confirmation") confirmation: String) {
        if (confirmation != "confirm") {
            sender.sendMessage("Use /em websync clear-secret confirm")
            return
        }
        service.clearSecret()
        sender.sendMessage("Website sync secret cleared and synchronization disabled.")
    }

    @Subcommand("websync test")
    @Permission(PERMISSION)
    fun test(@Context sender: CommandSender) {
        sender.sendMessage("Testing website sync authentication...")
        service.authenticatedTest { success, category ->
            sender.sendMessage(if (success) "Website sync authentication succeeded." else "Website sync test failed: $category")
        }
    }

    @Subcommand("websync validate")
    @Permission(PERMISSION)
    fun validate(@Context sender: CommandSender) {
        val report = service.validateLiveReport()
        if (report.errors.isEmpty()) sender.sendMessage("Website sync validation passed for all 71 exact stall identities.")
        else sender.sendMessage("Website sync validation failed (${report.errors.size} safe issue(s)): ${report.errors.take(10).joinToString()}")
        sender.sendMessage("Website sync diagnostics: ${report.diagnostics.joinToString()}")
    }

    @Subcommand("websync full")
    @Permission(PERMISSION)
    fun full(@Context sender: CommandSender) {
        sender.sendMessage(if (service.requestFull()) "Website full reconciliation requested." else "Website sync is not active.")
    }

    @Subcommand("websync enable")
    @Permission(PERMISSION)
    fun enable(@Context sender: CommandSender) {
        val result = service.enable()
        sender.sendMessage(if (result.config?.secretConfigured == true) "Website sync enabled; full reconciliation started."
            else "Website sync remains inactive until a valid secret and configuration are present.")
    }

    @Subcommand("websync disable")
    @Permission(PERMISSION)
    fun disable(@Context sender: CommandSender) {
        service.disable()
        sender.sendMessage("Website sync disabled; persistent pending state was retained.")
    }

    @Subcommand("websync retry")
    @Permission(PERMISSION)
    fun retry(@Context sender: CommandSender) {
        sender.sendMessage(if (service.retry()) "Website sync delivery retry requested." else "Website sync is not active.")
    }

    companion object {
        const val PERMISSION = "enthusiamarket.admin.websync"
        private fun yesNo(value: Boolean) = if (value) "yes" else "no"
        private fun age(value: Long?): String = value?.let { "${it / 1000}s" } ?: "none"
        private fun instant(value: Long?): String = value?.let { Instant.ofEpochMilli(it).toString() } ?: "never"
    }
}
