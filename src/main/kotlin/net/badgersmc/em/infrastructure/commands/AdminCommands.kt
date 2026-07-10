package net.badgersmc.em.infrastructure.commands

import net.badgersmc.em.application.AuctionLifecycleService
import net.badgersmc.em.application.AuctionResult
import net.badgersmc.em.application.ImportStallsService
import net.badgersmc.em.application.LimitResolutionService
import net.badgersmc.em.application.MassAuctionResult
import net.badgersmc.em.application.SellOfferService
import net.badgersmc.em.application.StallEvictionService
import net.badgersmc.em.application.StallMemberService
import net.badgersmc.em.application.StallOwnershipCounter
import net.badgersmc.em.application.StallSellbackService
import net.badgersmc.em.domain.ports.RegionMemberSync
import net.badgersmc.em.domain.stall.OwnerType
import net.badgersmc.em.domain.stall.StallState
import net.badgersmc.em.config.EnthusiaMarketConfig
import net.badgersmc.em.domain.auction.AuctionId
import net.badgersmc.em.domain.auction.AuctionRepository
import net.badgersmc.em.domain.stall.StallId
import net.badgersmc.em.domain.stall.StallRepository
import net.badgersmc.nexus.i18n.LangService
import net.badgersmc.em.application.GuildTradePolicyService
import net.badgersmc.em.domain.ports.GuildProvider
import net.badgersmc.em.interaction.gui.AuctionBrowserMenu
import net.badgersmc.em.interaction.gui.GuildTradePolicyMenu
import net.badgersmc.nexus.commands.annotations.Arg
import net.badgersmc.nexus.commands.annotations.Command
import net.badgersmc.nexus.commands.annotations.Context
import net.badgersmc.nexus.config.ConfigManager
import net.badgersmc.nexus.paper.commands.annotations.Permission
import net.badgersmc.nexus.paper.commands.annotations.Subcommand
import net.badgersmc.nexus.paper.commands.annotations.Suggests
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import net.badgersmc.nexus.scheduler.NexusScheduler
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID

@Command(name = "em", description = "EnthusiaMarket administrative commands", aliases = ["enthusiamarket"])
@Suppress("LongParameterList", "TooManyFunctions", "LargeClass")
class AdminCommands(
    private val service: ImportStallsService,
    private val stalls: StallRepository,
    private val config: EnthusiaMarketConfig,
    private val auctionService: AuctionLifecycleService,
    private val configManager: ConfigManager,
    private val auctions: AuctionRepository,
    @Suppress("UnusedPrivateProperty") private val plugin: JavaPlugin,
    private val lang: LangService,
    private val nexusScheduler: NexusScheduler,
    private val stallMembers: StallMemberService,
    private val sellOffers: SellOfferService,
    private val sellback: StallSellbackService,
    private val regionMembers: RegionMemberSync,
    private val entityCounter: net.badgersmc.em.application.StallEntityCounter,
    private val regionProvider: net.badgersmc.em.domain.ports.RegionProvider,
    private val stallInfo: net.badgersmc.em.application.StallInfoService,
    private val particleBorders: net.badgersmc.em.application.ParticleBorderService,
    private val stallEviction: StallEvictionService,
    private val limits: LimitResolutionService,
    private val ownership: StallOwnershipCounter,
    private val policyService: GuildTradePolicyService,
    private val guildProvider: GuildProvider,
    private val rentResync: net.badgersmc.em.application.RentTermsResyncService,
) {
    /** Pending `/em sellback` confirmations keyed on (player, stall). */
    private val pendingSellbacks =
        java.util.concurrent.ConcurrentHashMap<Pair<UUID, String>, java.time.Instant>()
    private val sellbackConfirmWindow: java.time.Duration = java.time.Duration.ofSeconds(30)

    @Subcommand("limit")
    @Permission("enthusiamarket.stall.info")
    fun limitInfo(@Context sender: CommandSender) {
        val player = sender as? Player ?: run { sender.sendMessage(lang.msg("shop.cmd.players_only")); return }
        val eff = limits.effectiveLimits(player.uniqueId)
        val counts = ownership.counts(player.uniqueId)
        player.sendMessage(lang.msg("admin.limit.header"))
        val totalCap = capLabel(eff.total)
        player.sendMessage(lang.msg("admin.limit.total", "used" to counts.total, "cap" to totalCap))
        for ((kind, cap) in eff.regionkinds) {
            player.sendMessage(lang.msg("admin.limit.kind", "kind" to kind, "used" to (counts.byKind[kind] ?: 0), "cap" to capLabel(cap)))
        }
    }

    private fun capLabel(cap: Int): String =
        if (cap < 0) net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
            .serialize(lang.msg("admin.limit.unlimited"))
        else cap.toString()

    @Subcommand("import")
    @Permission("enthusiamarket.admin.import")
    fun import(@Context sender: CommandSender) {
        val r = service.import(config.market.world, config.market.regionPrefix)
        sender.sendMessage(
            lang.msg(
                "admin.import.result",
                "created" to r.created,
                "skipped" to r.skipped,
                "provisioned" to r.provisioned,
                KEY_WORLD to config.market.world,
                KEY_REGION_PREFIX to config.market.regionPrefix
            )
        )
    }

    @Subcommand("rent resync")
    @Permission("enthusiamarket.admin.reload")
    fun rentResync(@Context sender: CommandSender) {
        // Push the current config default rent terms onto every existing stall. Needed after a
        // rent-config change (e.g. formula → flat) because terms are snapshotted per-stall at import.
        val n = rentResync.resync()
        sender.sendMessage(lang.msg("admin.rent.resync.result", "count" to n))
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
        val player = sender as? Player ?: run { sender.sendMessage(lang.msg("command.players_only")); return }
        val component = when (val result = auctionService.createAuction(
            StallId(stall), player.uniqueId, price, duration
        )) {
            is AuctionResult.Success -> lang.msg(
                "admin.auction.start.success",
                "stall" to result.auction.stallId,
                "starting_bid" to result.auction.startingBid
            )
            is AuctionResult.Failure -> lang.msg("admin.auction.start.failure", "reason" to result.reason)
            is AuctionResult.NotFound -> lang.msg("admin.auction.start.not_found")
        }
        sender.sendMessage(component)
    }

    @Subcommand("bid")
    @Permission("enthusiamarket.auction.bid")
    fun bid(
        @Context sender: CommandSender,
        @Arg("auction") auction: String,
        @Arg("amount") amount: Long
    ) {
        val player = sender as? Player ?: run { sender.sendMessage(lang.msg("command.players_only")); return }
        val component = when (val result = auctionService.placeBid(AuctionId(auction), player.uniqueId, amount,
            player.address.address.hostAddress ?: "unknown")) {
            is AuctionResult.Success -> lang.msg(
                "admin.bid.success",
                "amount" to (result.auction.highBid?.amount ?: amount),
                "stall" to result.auction.stallId.value
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
        AuctionBrowserMenu(auctions, stalls, auctionService, nexusScheduler, lang).open(sender)
    }

    @Subcommand("auction cancel")
    @Permission("enthusiamarket.admin")
    fun auctionCancel(
        @Context sender: CommandSender,
        @Arg("auction") auction: String
    ) {
        val player = sender as? Player ?: run { sender.sendMessage(lang.msg("command.players_only")); return }
        val component = when (val result = auctionService.cancelAuction(AuctionId(auction), player.uniqueId)) {
            is AuctionResult.Success -> lang.msg("admin.auction.cancel.success", "stall" to result.auction.stallId.value)
            is AuctionResult.Failure -> lang.msg("admin.auction.cancel.failure", "reason" to result.reason)
            is AuctionResult.NotFound -> lang.msg("admin.auction.cancel.not_found")
        }
        sender.sendMessage(component)
    }

    @Subcommand("auction cancelall")
    @Permission("enthusiamarket.admin")
    fun auctionCancelAll(@Context sender: CommandSender) {
        val count = auctionService.cancelAllAuctions()
        sender.sendMessage(lang.msg("admin.auction.cancelall.done", "count" to count))
    }

    @Subcommand("auction extend")
    @Permission("enthusiamarket.admin")
    fun auctionExtend(
        @Context sender: CommandSender,
        @Arg("auction") @Suggests("openAuctionIds") auction: String,
        @Arg("duration") duration: String,
    ) {
        val component = when (val result = auctionService.extendAuction(AuctionId(auction), duration)) {
            is AuctionResult.Success -> lang.msg(
                "admin.auction.extend.success",
                "id" to result.auction.id,
                "stall" to result.auction.stallId,
                "end_at" to result.auction.endAt.toString()
            )
            is AuctionResult.Failure -> lang.msg("admin.auction.extend.failure", "reason" to result.reason)
            is AuctionResult.NotFound -> lang.msg("admin.auction.extend.not_found")
        }
        sender.sendMessage(component)
    }

    @Subcommand("auction clear")
    @Permission("enthusiamarket.admin")
    fun auctionClear(
        @Context sender: CommandSender,
        @Arg("stall") stall: String,
    ) {
        val cleared = auctionService.clearStaleBidData(StallId(stall))
        sender.sendMessage(lang.msg("admin.auction.clear.done", "stall" to stall, "count" to cleared))
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
        prunePendingSellbacks()
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
                sender.sendMessage(lang.msg("sellback.warn.belongings"))
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

    // ----- WG resync (operator backfill) -----

    @Subcommand("rg resync")
    @Permission("enthusiamarket.admin")
    @Suppress("NestedBlockDepth")
    fun rgResync(@Context sender: CommandSender) {
        var fixed = 0
        var skipped = 0
        var errors = 0
        for (stall in stalls.all()) {
            when (stall.state) {
                StallState.OWNED, StallState.GRACE -> when (stall.owner.type) {
                    OwnerType.SOLO -> try {
                        val uuid = UUID.fromString(stall.owner.id)
                        // Rebuild full ACL: clear first, set owner, then replay members.
                        regionMembers.clearOwnersAndMembers(stall.world, stall.regionId)
                        regionMembers.setOwner(stall.world, stall.regionId, uuid)
                        for (memberId in stall.members) {
                            regionMembers.addMember(stall.world, stall.regionId, memberId)
                        }
                        fixed++
                    } catch (_: Exception) {
                        errors++
                    }
                    OwnerType.GUILD -> skipped++  // no auto WG bridge for guilds yet
                    OwnerType.NONE -> Unit
                }
                StallState.UNOWNED -> try {
                    regionMembers.clearOwnersAndMembers(stall.world, stall.regionId)
                    fixed++
                } catch (_: Exception) {
                    errors++
                }
                // Active auctions left alone — the winning bid will
                // run setOwner via settleWithWinner at expiry.
                StallState.AUCTIONING,
                StallState.RE_AUCTIONING,
                StallState.EMERGENCY_AUCTIONING -> skipped++
            }
        }
        sender.sendMessage(lang.msg(
            "admin.rg_resync.result",
            "fixed" to fixed, "skipped" to skipped, "errors" to errors,
        ))
    }

    @Subcommand("stall setkind")
    @Permission("enthusiamarket.stall.setkind")
    fun stallSetKind(@Context sender: CommandSender, @Arg("stall") stallId: String, @Arg("kind") kind: String) {
        val ok = applySetKind(stalls, stallId, kind)
        sender.sendMessage(
            if (ok) lang.msg("stall.setkind.ok", "stall" to stallId, "kind" to kind)
            else lang.msg("stall.setkind.missing", "stall" to stallId)
        )
    }

    @Subcommand("stall entitylimit set")
    @Permission("enthusiamarket.stall.entitylimit")
    fun stallEntityLimit(@Context sender: CommandSender, @Arg("stall") stallId: String, @Arg("type") type: String, @Arg("extra") extra: Int) {
        val ok = applyEntityLimit(stalls, stallId, type, extra)
        sender.sendMessage(
            if (ok) lang.msg("stall.entitylimit.ok", "stall" to stallId, "type" to type, "extra" to extra)
            else lang.msg("stall.entitylimit.missing", "stall" to stallId)
        )
    }

    @Subcommand("stall recount")
    @Permission("enthusiamarket.stall.recount")
    fun stallRecount(@Context sender: CommandSender, @Arg("stall") stallId: String) {
        val stall = stalls.findById(StallId(stallId))
        if (stall == null) {
            sender.sendMessage(lang.msg("stall.recount.missing", "stall" to stallId))
            return
        }
        val world = org.bukkit.Bukkit.getWorld(stall.world)
        val bounds = regionProvider.bounds(stall.world, stall.regionId)
        val counts = HashMap<String, Int>()
        if (world != null && bounds != null) {
            // Bounded scan over the region's cuboid (not the whole world) — a
            // stall region is a cuboid, so its bounding box equals the region.
            val box = org.bukkit.util.BoundingBox(
                bounds.minX.toDouble(), bounds.minY.toDouble(), bounds.minZ.toDouble(),
                (bounds.maxX + 1).toDouble(), (bounds.maxY + 1).toDouble(), (bounds.maxZ + 1).toDouble(),
            )
            for (entity in world.getNearbyEntities(box)) {
                val t = entity.type.name.lowercase(java.util.Locale.ROOT)
                counts[t] = (counts[t] ?: 0) + 1
            }
        }
        entityCounter.recount(stallId, counts)
        sender.sendMessage(lang.msg("stall.recount.ok", "stall" to stallId, "total" to counts.values.sum()))
    }

    @Subcommand("stall info")
    @Permission("enthusiamarket.stall.info")
    fun stallInfo(@Context sender: CommandSender, @Arg("stall") stallId: String) {
        val info = stallInfo.infoFor(StallId(stallId))
        if (info == null) {
            sender.sendMessage(lang.msg("stall.info.missing", "stall" to stallId))
            return
        }
        sender.sendMessage(renderInfoCard(info))
    }

    private fun renderInfoCard(info: net.badgersmc.em.application.StallInfo) = lang.msg(
        "stall.info.card",
        "stall" to info.stallId,
        "kind" to info.kind,
        "state" to info.state.name,
        "owner" to info.ownerName,
        "members" to info.memberCount,
        "rent" to info.currentRent,
        "next" to (info.nextRentAt?.toString() ?: "—"),
        "width" to info.width,
        "height" to info.height,
        "length" to info.length,
        "available" to if (info.available) "yes" else "no",
    )

    @Subcommand("stall outline")
    @Permission("enthusiamarket.stall.outline")
    fun stallOutline(@Context sender: CommandSender, @Arg("stall") stallId: String, @Arg("seconds") seconds: Int) {
        val player = sender as? Player ?: run {
            sender.sendMessage(lang.msg("stall.outline.player_only"))
            return
        }
        val stall = stalls.findById(StallId(stallId))
        if (stall == null) {
            sender.sendMessage(lang.msg("stall.outline.missing", "stall" to stallId))
            return
        }
        val bounds = regionProvider.bounds(stall.world, stall.regionId)
        if (bounds == null) {
            sender.sendMessage(lang.msg("stall.outline.no_region", "stall" to stallId))
            return
        }
        val dur = if (seconds <= 0) 10 else seconds
        particleBorders.addOutline(
            player.uniqueId, stallId, stall.world, bounds,
            java.time.Instant.now().plusSeconds(dur.toLong())
        )
        sender.sendMessage(lang.msg("stall.outline.ok", "stall" to stallId, "seconds" to dur))
    }

    // ----- Guild trade policy -----

    @Subcommand("guild policy")
    @Permission("enthusiamarket.guild.policy")
    fun guildPolicy(@Context sender: CommandSender) {
        val player = sender as? Player ?: run { sender.sendMessage(lang.msg("command.players_only")); return }
        val guild = guildProvider.guildOf(player.uniqueId) ?: run { player.sendMessage(lang.msg("guildpolicy.not_in_guild")); return }
        if (!guildProvider.hasShopPermission(player.uniqueId, guild.id, GuildProvider.GuildPermission.MANAGE_SHOPS)) {
            player.sendMessage(lang.msg("guildpolicy.no_permission")); return
        }
        GuildTradePolicyMenu(player.uniqueId, guild.id, policyService, guildProvider, lang).open(player)
    }

    // ----- Admin evict (force unclaim) -----

    @Subcommand("evict")
    @Permission("enthusiamarket.admin.evict")
    fun evict(
        @Context sender: CommandSender,
        @Arg("stall") stall: String,
    ) {
        val msg = when (stallEviction.evict(StallId(stall))) {
            is StallEvictionService.Result.Evicted ->
                lang.msg("admin.evict.success", "stall" to stall)
            is StallEvictionService.Result.NotFound ->
                lang.msg("admin.evict.not_found", "stall" to stall)
            is StallEvictionService.Result.NotOwned ->
                lang.msg("admin.evict.not_owned", "stall" to stall)
        }
        sender.sendMessage(msg)
    }

    @Subcommand("help")
    @Permission("enthusiamarket.shop.help")
    fun help(
        @Context sender: CommandSender,
        @Arg("topic") topicSlug: String? = null,
    ) {
        if (topicSlug == null) {
            sender.sendMessage(net.badgersmc.em.interaction.help.HelpTopicsRenderer.renderTopicMenu())
            return
        }
        val topic = net.badgersmc.em.interaction.help.HelpTopics.bySlug(topicSlug)
        if (topic == null) {
            sender.sendMessage(lang.msg("shop.help.unknown_topic", "topic" to topicSlug))
            return
        }
        sender.sendMessage(net.badgersmc.em.interaction.help.HelpTopicsRenderer.renderTopicPage(topic))
    }

    /**
     * Drop expired entries from [pendingSellbacks] so the map doesn't
     * leak across stagings that never confirm. Called inline on every
     * sellback subcommand — keeps the cost O(n) on small n with no
     * background scheduler.
     */
    private fun prunePendingSellbacks() {
        val now = java.time.Instant.now()
        pendingSellbacks.entries.removeIf {
            java.time.Duration.between(it.value, now) > sellbackConfirmWindow
        }
    }


    internal companion object {
        const val KEY_WORLD = "world"
        const val KEY_REGION_PREFIX = "region_prefix"

        /** Set a stall's region kind. Returns false when the stall is missing. */
        fun applySetKind(repo: StallRepository, stallId: String, kind: String): Boolean {
            val stall = repo.findById(StallId(stallId)) ?: return false
            repo.save(stall.copy(kind = kind))
            return true
        }

        /** Set a per-stall per-type entity-limit override. Returns false when missing. */
        fun applyEntityLimit(repo: StallRepository, stallId: String, type: String, extra: Int): Boolean {
            val stall = repo.findById(StallId(stallId)) ?: return false
            val merged = stall.extraEntities.toMutableMap()
            merged[type.lowercase()] = extra
            repo.save(stall.copy(extraEntities = merged))
            return true
        }
    }
}
