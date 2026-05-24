package net.badgersmc.em.infrastructure.commands

import net.badgersmc.em.application.ImportStallsService
import net.badgersmc.em.config.EnthusiaMarketConfig
import net.badgersmc.em.domain.stall.StallRepository
import net.badgersmc.em.application.AuctionLifecycleService
import net.badgersmc.em.application.AuctionResult
import net.badgersmc.em.domain.auction.AuctionId
import net.badgersmc.em.domain.stall.StallId
import net.badgersmc.nexus.commands.annotations.Command
import net.badgersmc.nexus.commands.annotations.Context
import net.badgersmc.nexus.paper.commands.annotations.Permission
import net.badgersmc.nexus.paper.commands.annotations.Subcommand
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.util.UUID

@Command(name = "em", description = "EnthusiaMarket administrative commands", aliases = ["enthusiamarket"])
class AdminCommands(
    private val service: ImportStallsService,
    private val stalls: StallRepository,
    private val config: EnthusiaMarketConfig,
    private val auctionService: AuctionLifecycleService
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

    @Subcommand("auction start")
    @Permission("enthusiamarket.admin")
    fun auctionStart(@Context sender: CommandSender, stall: String, price: Long, duration: String? = null) {
        val result = auctionService.createAuction(StallId(stall), extractSenderUuid(sender), price, duration)
        val msg = when (result) {
            is AuctionResult.Success -> "Auction created: ${result.auction.id} for stall ${result.auction.stallId} starting at ${result.auction.startingBid}"
            is AuctionResult.Failure -> "Failed to create auction: ${result.reason}"
            is AuctionResult.NotFound -> "Stall not found"
        }
        sender.sendMessage("[EnthusiaMarket] $msg")
    }

    @Subcommand("bid")
    fun bid(@Context sender: CommandSender, auction: String, amount: Long) {
        val result = auctionService.placeBid(AuctionId(auction), extractSenderUuid(sender), amount)
        val msg = when (result) {
            is AuctionResult.Success -> "Bid placed: ${result.auction.highBid?.amount ?: amount} on auction ${result.auction.id}"
            is AuctionResult.Failure -> "Bid rejected: ${result.reason}"
            is AuctionResult.NotFound -> "Auction not found"
        }
        sender.sendMessage("[EnthusiaMarket] $msg")
    }

    @Subcommand("auction cancel")
    @Permission("enthusiamarket.admin")
    fun auctionCancel(@Context sender: CommandSender, auction: String) {
        val result = auctionService.cancelAuction(AuctionId(auction), extractSenderUuid(sender))
        val msg = when (result) {
            is AuctionResult.Success -> "Auction cancelled: ${result.auction.id}"
            is AuctionResult.Failure -> "Failed to cancel auction: ${result.reason}"
            is AuctionResult.NotFound -> "Auction not found"
        }
        sender.sendMessage("[EnthusiaMarket] $msg")
    }

    /** Extract sender UUID, preferring Player sender. */
    private fun extractSenderUuid(sender: CommandSender): UUID {
        return if (sender is Player) sender.uniqueId else UUID.randomUUID()
    }
}