package net.badgersmc.em.infrastructure.commands

import net.badgersmc.em.application.AuctionLifecycleService
import net.badgersmc.em.application.AuctionResult
import net.badgersmc.em.application.ImportStallsService
import net.badgersmc.em.application.MassAuctionResult
import net.badgersmc.em.application.StallMemberService
import net.badgersmc.em.config.EnthusiaMarketConfig
import net.badgersmc.em.domain.auction.AuctionId
import net.badgersmc.em.domain.auction.AuctionRepository
import net.badgersmc.em.domain.stall.StallId
import net.badgersmc.em.domain.stall.StallRepository
import net.badgersmc.nexus.i18n.LangService
import net.badgersmc.em.interaction.gui.AuctionBrowserMenu
import net.badgersmc.nexus.commands.annotations.Arg
import net.badgersmc.nexus.commands.annotations.Command
import net.badgersmc.nexus.commands.annotations.Context
import net.badgersmc.nexus.config.ConfigManager
import net.badgersmc.nexus.paper.commands.annotations.Permission
import net.badgersmc.nexus.paper.commands.annotations.Subcommand
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import net.badgersmc.nexus.scheduler.NexusScheduler
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID

@Command(name = "em", description = "EnthusiaMarket administrative commands", aliases = ["enthusiamarket"])
class AdminCommands(
    private val service: ImportStallsService,
    private val stalls: StallRepository,
    private val config: EnthusiaMarketConfig,
    private val auctionService: AuctionLifecycleService,
    private val configManager: ConfigManager,
    private val auctions: AuctionRepository,
    private val plugin: JavaPlugin,
    private val lang: LangService,
    private val nexusScheduler: NexusScheduler,
    private val stallMembers: StallMemberService,
) {
    @Subcommand("import")
    @Permission("enthusiamarket.admin.import")
    fun import(@Context sender: CommandSender) {
        val r = service.import(config.market.world, config.market.regionPrefix)
        sender.sendMessage(
            lang.msg(
                "admin.import.result",
                "created" to r.created,
                "skipped" to r.skipped,
                "world" to config.market.world,
                "region_prefix" to config.market.regionPrefix
            )
        )
    }

    @Subcommand("reload")
    @Permission("enthusiamarket.admin.reload")
    fun reload(@Context sender: CommandSender) {
        try {
            configManager.reload(EnthusiaMarketConfig::class)
            lang.reload()
            sender.sendMessage(
                lang.msg(
                    "admin.reload.success",
                    "world" to config.market.world,
                    "region_prefix" to config.market.regionPrefix
                )
            )
        } catch (e: Exception) {
            sender.sendMessage(lang.msg("admin.reload.failure", "reason" to (e.message ?: "unknown error")))
        }
    }

    @Subcommand("list")
    @Permission("enthusiamarket.admin.list")
    fun list(@Context sender: CommandSender) {
        for (s in stalls.all()) {
            sender.sendMessage(
                lang.msg(
                    "admin.list.line",
                    "id" to s.id,
                    "state" to s.state,
                    "world" to s.world,
                    "region" to s.regionId
                )
            )
        }
    }

    @Subcommand("auction start")
    @Permission("enthusiamarket.admin")
    fun auctionStart(
        @Context sender: CommandSender,
        @Arg("stall") stall: String,
        @Arg("price") price: Long,
        @Arg("duration") duration: String? = null
    ) {
        val component = when (val result = auctionService.createAuction(
            StallId(stall), extractSenderUuid(sender), price, duration
        )) {
            is AuctionResult.Success -> lang.msg(
                "admin.auction.start.success",
                "id" to result.auction.id,
                "stall" to result.auction.stallId,
                "starting_bid" to result.auction.startingBid
            )
            is AuctionResult.Failure -> lang.msg("admin.auction.start.failure", "reason" to result.reason)
            is AuctionResult.NotFound -> lang.msg("admin.auction.start.not_found")
        }
        sender.sendMessage(component)
    }

    @Subcommand("bid")
    @Permission("enthusiamarket.admin")
    fun bid(
        @Context sender: CommandSender,
        @Arg("auction") auction: String,
        @Arg("amount") amount: Long
    ) {
        val component = when (val result = auctionService.placeBid(AuctionId(auction), extractSenderUuid(sender), amount)) {
            is AuctionResult.Success -> lang.msg(
                "admin.bid.success",
                "amount" to (result.auction.highBid?.amount ?: amount),
                "id" to result.auction.id
            )
            is AuctionResult.Failure -> lang.msg("admin.bid.failure", "reason" to result.reason)
            is AuctionResult.NotFound -> lang.msg("admin.bid.not_found")
        }
        sender.sendMessage(component)
    }

    @Subcommand("auction startall")
    @Permission("enthusiamarket.admin")
    fun auctionStartAll(
        @Context sender: CommandSender,
        @Arg("price") price: Long,
        @Arg("duration") duration: String? = null
    ) {
        val component = when (val result = auctionService.startMassAuction(price, duration)) {
            is MassAuctionResult.Report -> lang.msg(
                "admin.auction.startall.result",
                "created" to result.created,
                "skipped" to result.skipped,
                "errors" to result.errors
            )
            is MassAuctionResult.Invalid -> lang.msg("admin.auction.startall.failure", "reason" to result.reason)
        }
        sender.sendMessage(component)
    }

    @Subcommand("auctions")
    @Permission("enthusiamarket.auction.list")
    fun auctionsBrowse(@Context sender: CommandSender) {
        if (sender !is Player) {
            sender.sendMessage(lang.msg("command.players_only"))
            return
        }
        AuctionBrowserMenu(auctions, stalls, nexusScheduler, lang).open(sender)
    }

    @Subcommand("auction cancel")
    @Permission("enthusiamarket.admin")
    fun auctionCancel(
        @Context sender: CommandSender,
        @Arg("auction") auction: String
    ) {
        val component = when (val result = auctionService.cancelAuction(AuctionId(auction), extractSenderUuid(sender))) {
            is AuctionResult.Success -> lang.msg("admin.auction.cancel.success", "id" to result.auction.id)
            is AuctionResult.Failure -> lang.msg("admin.auction.cancel.failure", "reason" to result.reason)
            is AuctionResult.NotFound -> lang.msg("admin.auction.cancel.not_found")
        }
        sender.sendMessage(component)
    }

    @Subcommand("stall members add")
    @Permission("enthusiamarket.stall.members")
    fun membersAdd(
        @Context sender: Player,
        @Arg("stall") stall: String,
        @Arg("player") player: String,
    ) {
        val targetUuid = org.bukkit.Bukkit.getOfflinePlayer(player).uniqueId
        renderMemberMutation(sender, stall, "added") {
            stallMembers.addMember(StallId(stall), sender.uniqueId, targetUuid) to targetUuid
        }
    }

    @Subcommand("stall members remove")
    @Permission("enthusiamarket.stall.members")
    fun membersRemove(
        @Context sender: Player,
        @Arg("stall") stall: String,
        @Arg("player") player: String,
    ) {
        val targetUuid = org.bukkit.Bukkit.getOfflinePlayer(player).uniqueId
        renderMemberMutation(sender, stall, "removed") {
            stallMembers.removeMember(StallId(stall), sender.uniqueId, targetUuid) to targetUuid
        }
    }

    @Subcommand("stall members list")
    @Permission("enthusiamarket.stall.members")
    fun membersList(
        @Context sender: Player,
        @Arg("stall") stall: String,
    ) {
        when (val r = stallMembers.listMembers(StallId(stall), sender.uniqueId)) {
            is StallMemberService.Result.NotFound ->
                sender.sendMessage(lang.msg("stall.members.not_found", "stall" to stall))
            is StallMemberService.Result.NotAuthorised ->
                sender.sendMessage(lang.msg("stall.members.not_authorised", "stall" to stall))
            is StallMemberService.Result.Rejected ->
                sender.sendMessage(lang.msg("stall.members.rejected", "reason" to r.reason))
            is StallMemberService.Result.Success -> {
                val members = r.stall.members
                if (members.isEmpty()) {
                    sender.sendMessage(lang.msg("stall.members.list_empty", "stall" to stall))
                } else {
                    sender.sendMessage(
                        lang.msg(
                            "stall.members.list_header",
                            "stall" to stall,
                            "count" to members.size,
                        )
                    )
                    for (uuid in members) {
                        val name = org.bukkit.Bukkit.getOfflinePlayer(uuid).name ?: uuid.toString()
                        sender.sendMessage(lang.msg("stall.members.list_entry", "player" to name))
                    }
                }
            }
        }
    }

    private inline fun renderMemberMutation(
        sender: Player,
        stall: String,
        successKey: String,
        op: () -> Pair<StallMemberService.Result, UUID>,
    ) {
        val (result, targetUuid) = op()
        val targetName = org.bukkit.Bukkit.getOfflinePlayer(targetUuid).name ?: targetUuid.toString()
        val msg = when (result) {
            is StallMemberService.Result.Success ->
                lang.msg("stall.members.$successKey", "player" to targetName, "stall" to stall)
            is StallMemberService.Result.NotFound ->
                lang.msg("stall.members.not_found", "stall" to stall)
            is StallMemberService.Result.NotAuthorised ->
                lang.msg("stall.members.not_authorised", "stall" to stall)
            is StallMemberService.Result.Rejected ->
                lang.msg("stall.members.rejected", "reason" to result.reason)
        }
        sender.sendMessage(msg)
    }

    /** Extract sender UUID, preferring Player sender. */
    private fun extractSenderUuid(sender: CommandSender): UUID {
        return if (sender is Player) sender.uniqueId else UUID.randomUUID()
    }
}
