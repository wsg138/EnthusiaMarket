package net.badgersmc.em.infrastructure.commands

import net.badgersmc.em.application.AuctionLifecycleService
import net.badgersmc.em.application.AuctionResult
import net.badgersmc.em.application.ImportStallsService
import net.badgersmc.em.application.MassAuctionResult
import net.badgersmc.em.application.SellOfferService
import net.badgersmc.em.application.StallMemberService
import net.badgersmc.em.application.StallSellbackService
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
    private val sellOffers: SellOfferService,
    private val sellback: StallSellbackService,
) {
    /** Pending `/em sellback` confirmations keyed on (player, stall). */
    private val pendingSellbacks =
        java.util.concurrent.ConcurrentHashMap<Pair<UUID, String>, java.time.Instant>()
    private val sellbackConfirmWindow: java.time.Duration = java.time.Duration.ofSeconds(30)
    @Subcommand("import")
    @Permission("enthusiamarket.admin.import")
    fun import(@Context sender: CommandSender) {
        val r = service.import(config.market.world, config.market.regionPrefix)
        sender.sendMessage(
            lang.msg(
                "admin.import.result",
                "created" to r.created,
                "skipped" to r.skipped,
                KEY_WORLD to config.market.world,
                KEY_REGION_PREFIX to config.market.regionPrefix
            )
        )
    }

    @Suppress("TooGenericExceptionCaught")
    @Subcommand("reload")
    @Permission("enthusiamarket.admin.reload")
    fun reload(@Context sender: CommandSender) {
        try {
            configManager.reload(EnthusiaMarketConfig::class)
            lang.reload()
            sender.sendMessage(
                lang.msg(
                    "admin.reload.success",
                    KEY_WORLD to config.market.world,
                    KEY_REGION_PREFIX to config.market.regionPrefix
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
        val offline = org.bukkit.Bukkit.getOfflinePlayer(player)
        if (!offline.hasPlayedBefore()) {
            sender.sendMessage(lang.msg("stall.members.unknown_player", "player" to player))
            return
        }
        val targetUuid = offline.uniqueId
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
        val offline = org.bukkit.Bukkit.getOfflinePlayer(player)
        if (!offline.hasPlayedBefore()) {
            sender.sendMessage(lang.msg("stall.members.unknown_player", "player" to player))
            return
        }
        val targetUuid = offline.uniqueId
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

    // ----- Sell offers (REQ-260..264) -----

    @Subcommand("stall offer")
    @Permission("enthusiamarket.stall.offer")
    fun stallOffer(
        @Context sender: Player,
        @Arg("stall") stall: String,
        @Arg("price") price: Long,
    ) {
        val msg = when (val r = sellOffers.create(StallId(stall), sender.uniqueId, price)) {
            is SellOfferService.Result.Created ->
                lang.msg("offer.created", "stall" to stall, "price" to r.offer.price)
            is SellOfferService.Result.NotFound ->
                lang.msg("offer.stall_not_found", "stall" to stall)
            is SellOfferService.Result.NotAuthorised ->
                lang.msg("offer.not_authorised", "stall" to stall)
            is SellOfferService.Result.AuctionOpen ->
                lang.msg("offer.auction_open", "stall" to stall)
            is SellOfferService.Result.OfferOpen ->
                lang.msg("offer.rejected", "reason" to "offer already exists")
            is SellOfferService.Result.Rejected ->
                lang.msg("offer.rejected", "reason" to r.reason)
            is SellOfferService.Result.Cancelled,
            is SellOfferService.Result.Purchased ->
                lang.msg("offer.rejected", "reason" to "unexpected")
        }
        sender.sendMessage(msg)
    }

    @Subcommand("stall offer cancel")
    @Permission("enthusiamarket.stall.offer")
    fun stallOfferCancel(
        @Context sender: Player,
        @Arg("stall") stall: String,
    ) {
        val msg = when (val r = sellOffers.cancel(StallId(stall), sender.uniqueId)) {
            is SellOfferService.Result.Cancelled ->
                lang.msg("offer.cancelled", "stall" to stall)
            is SellOfferService.Result.NotFound ->
                lang.msg("offer.not_found", "stall" to stall)
            is SellOfferService.Result.NotAuthorised ->
                lang.msg("offer.not_authorised", "stall" to stall)
            is SellOfferService.Result.AuctionOpen,
            is SellOfferService.Result.OfferOpen,
            is SellOfferService.Result.Created,
            is SellOfferService.Result.Purchased,
            is SellOfferService.Result.Rejected ->
                lang.msg("offer.rejected", "reason" to "unexpected")
        }
        sender.sendMessage(msg)
    }

    @Subcommand("stall buy")
    @Permission("enthusiamarket.stall.buy")
    fun stallBuy(
        @Context sender: Player,
        @Arg("stall") stall: String,
    ) {
        val msg = when (val r = sellOffers.purchase(StallId(stall), sender.uniqueId)) {
            is SellOfferService.Result.Purchased -> {
                val total = r.offer.price + r.tax
                lang.msg(
                    "offer.purchased",
                    "stall" to stall,
                    "price" to r.offer.price,
                    "tax" to r.tax,
                    "total" to total,
                )
            }
            is SellOfferService.Result.NotFound ->
                lang.msg("offer.not_found", "stall" to stall)
            is SellOfferService.Result.NotAuthorised ->
                lang.msg("offer.not_authorised", "stall" to stall)
            is SellOfferService.Result.Rejected ->
                lang.msg("offer.rejected", "reason" to r.reason)
            is SellOfferService.Result.AuctionOpen,
            is SellOfferService.Result.OfferOpen,
            is SellOfferService.Result.Created,
            is SellOfferService.Result.Cancelled ->
                lang.msg("offer.rejected", "reason" to "unexpected")
        }
        sender.sendMessage(msg)
    }

    // ----- Sellback (voluntary relinquish + refund) -----

    @Subcommand("sellback")
    @Permission("enthusiamarket.stall.sellback")
    fun sellback(
        @Context sender: Player,
        @Arg("stall") stall: String,
    ) {
        when (val r = sellback.quote(StallId(stall), sender.uniqueId)) {
            is StallSellbackService.QuoteResult.NotFound ->
                sender.sendMessage(lang.msg("sellback.stall_not_found", "stall" to stall))
            is StallSellbackService.QuoteResult.NotOwned ->
                sender.sendMessage(lang.msg("sellback.not_owned", "stall" to stall))
            is StallSellbackService.QuoteResult.NotAuthorised ->
                sender.sendMessage(lang.msg("sellback.not_authorised", "stall" to stall))
            is StallSellbackService.QuoteResult.Ok -> {
                pendingSellbacks[sender.uniqueId to stall] = java.time.Instant.now()
                sender.sendMessage(lang.msg(
                    "sellback.warn.header",
                    "stall" to stall,
                    "refund" to r.quote.refund,
                    "periods" to r.quote.refundedPeriods,
                    "shops" to r.quote.shopCount,
                    "seconds" to sellbackConfirmWindow.seconds,
                ))
                sender.sendMessage(lang.msg("sellback.warn.wipe", "shops" to r.quote.shopCount))
                sender.sendMessage(lang.msg("sellback.warn.schematic"))
                sender.sendMessage(lang.msg("sellback.warn.confirm", "stall" to stall))
            }
        }
    }

    @Subcommand("sellback confirm")
    @Permission("enthusiamarket.stall.sellback")
    fun sellbackConfirm(
        @Context sender: Player,
        @Arg("stall") stall: String,
    ) {
        val key = sender.uniqueId to stall
        val stagedAt = pendingSellbacks[key]
        if (stagedAt == null ||
            java.time.Duration.between(stagedAt, java.time.Instant.now()) > sellbackConfirmWindow
        ) {
            pendingSellbacks.remove(key)
            sender.sendMessage(lang.msg("sellback.no_pending", "stall" to stall))
            return
        }
        pendingSellbacks.remove(key)

        val msg = when (val r = sellback.execute(StallId(stall), sender.uniqueId)) {
            is StallSellbackService.ExecuteResult.Sold -> lang.msg(
                "sellback.success",
                "stall" to stall,
                "refund" to r.refund,
                "shops" to r.shopsWiped,
            )
            is StallSellbackService.ExecuteResult.NotFound ->
                lang.msg("sellback.stall_not_found", "stall" to stall)
            is StallSellbackService.ExecuteResult.NotOwned ->
                lang.msg("sellback.not_owned", "stall" to stall)
            is StallSellbackService.ExecuteResult.NotAuthorised ->
                lang.msg("sellback.not_authorised", "stall" to stall)
            is StallSellbackService.ExecuteResult.Rejected ->
                lang.msg("sellback.rejected", "reason" to r.reason)
        }
        sender.sendMessage(msg)
    }

    /** Extract sender UUID, preferring Player sender. */
    private fun extractSenderUuid(sender: CommandSender): UUID {
        return if (sender is Player) sender.uniqueId else UUID.randomUUID()
    }

    private companion object {
        const val KEY_WORLD = "world"
        const val KEY_REGION_PREFIX = "region_prefix"
    }
}
