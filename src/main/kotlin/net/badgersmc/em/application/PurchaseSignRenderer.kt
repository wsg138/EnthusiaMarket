package net.badgersmc.em.application

import net.badgersmc.em.config.EnthusiaMarketConfig
import net.badgersmc.em.domain.auction.Auction
import net.badgersmc.em.domain.auction.AuctionRepository
import net.badgersmc.em.domain.sign.PurchaseSign
import net.badgersmc.em.domain.stall.Stall
import net.badgersmc.em.domain.stall.StallId
import net.badgersmc.em.domain.stall.StallRepository
import net.badgersmc.em.domain.stall.StallState
import net.badgersmc.nexus.annotations.Service
import net.badgersmc.nexus.i18n.LangService
import net.kyori.adventure.text.Component
import java.time.Duration
import java.time.Instant

/**
 * Renders the four lines of a [PurchaseSign] based on the current
 * stall state.
 *
 * - **UNOWNED**: "Click to buy" + price.
 * - **AUCTIONING**: current bid + "/em bid" — informational, sign
 *   click is a no-op in this state per the one-shot initial-auction
 *   design.
 * - **OWNED / GRACE**: owner display name + time until next rent.
 *   Right-click prompts a confirm + extension flow handled by
 *   [StallRentExtensionService].
 *
 * Returned list is always exactly 4 entries — the Bukkit Sign API
 * requires every line, blanks included.
 */
@Service
class PurchaseSignRenderer(
    private val stalls: StallRepository,
    private val auctions: AuctionRepository,
    private val owners: OwnerNameResolver,
    private val config: EnthusiaMarketConfig,
    private val lang: LangService,
) {

    @Volatile
    private var openAuctionCache: Map<StallId, Auction> = emptyMap()

    /**
     * Pre-load all open auctions into memory so the timer-driven refresh loop
     * avoids N separate DB round-trips (one per AUCTIONING sign). Call from
     * [PurchaseSignRefreshListener.refreshLoaded] before the render loop.
     */
    fun refreshAuctionCache() {
        openAuctionCache = auctions.allOpen().associateBy { it.stallId }
    }

    fun render(sign: PurchaseSign): List<Component> {
        val stall = stalls.findById(sign.stallId)
            ?: return missing(sign)

        return when (stall.state) {
            StallState.UNOWNED -> buyable(sign)
            StallState.EMERGENCY_AUCTIONING -> emergencyAuction(sign)
            StallState.AUCTIONING, StallState.RE_AUCTIONING ->
                auctionLive(sign)
            StallState.OWNED, StallState.GRACE -> ownedLines(sign, stall)
        }
    }

    private fun buyable(sign: PurchaseSign): List<Component> = listOf(
        lang.msg("purchase_sign.buyable.line1"),
        lang.msg("purchase_sign.buyable.line2", "stall" to sign.stallId.value),
        lang.msg("purchase_sign.buyable.line3", "price" to sign.price),
        lang.msg("purchase_sign.buyable.line4"),
    )

    private fun auctionLive(sign: PurchaseSign): List<Component> {
        val auction = openAuctionCache[sign.stallId] ?: auctions.findOpenByStall(sign.stallId)
        val currentBid = auction?.highBid?.amount ?: auction?.startingBid ?: 0L
        return listOf(
            lang.msg("purchase_sign.auction.line1"),
            lang.msg("purchase_sign.auction.line2", "stall" to sign.stallId.value),
            lang.msg("purchase_sign.auction.line3", "bid" to currentBid),
            lang.msg("purchase_sign.auction.line4"),
        )
    }

    /** Renders emergency-auction signs with a distinct dark-red colour scheme
     *  so players can visually distinguish them from standard auctions. */
    private fun emergencyAuction(sign: PurchaseSign): List<Component> {
        val auction = openAuctionCache[sign.stallId] ?: auctions.findOpenByStall(sign.stallId)
        val currentBid = auction?.highBid?.amount ?: auction?.startingBid ?: 0L
        return listOf(
            lang.msg("purchase_sign.emergency_auction.line1"),
            lang.msg("purchase_sign.emergency_auction.line2", "stall" to sign.stallId.value),
            lang.msg("purchase_sign.emergency_auction.line3", "bid" to currentBid),
            lang.msg("purchase_sign.emergency_auction.line4"),
        )
    }

    private fun ownedLines(@Suppress("UnusedParameter") sign: PurchaseSign, stall: Stall): List<Component> {
        val ownerName = owners.displayNameFor(stall.owner)
        val nextRent = stall.nextRentAt ?: fallbackNextRent(stall)
        val rentAmount = stall.rentTerms.dailyRent(stall.winningBid)
        return listOf(
            lang.msg("purchase_sign.owned.line1", "stall" to stall.id.value),
            lang.msg("purchase_sign.owned.line2", "owner" to ownerName),
            lang.msg("purchase_sign.owned.line3", "rent" to rentAmount),
            formatCountdown(nextRent),
        )
    }

    private fun formatCountdown(nextRent: Instant): Component {
        val remaining = Duration.between(Instant.now(), nextRent)
        if (remaining.isZero || remaining.isNegative) return lang.msg("purchase_sign.owned.overdue")
        val days = remaining.toDays()
        val hours = remaining.toHours() % 24
        val minutes = remaining.toMinutes() % 60
        val seconds = remaining.seconds % 60
        val time =
            "${days.toString().padStart(2, '0')}:" +
                "${hours.toString().padStart(2, '0')}:" +
                "${minutes.toString().padStart(2, '0')}:" +
                "${seconds.toString().padStart(2, '0')}"
        return lang.msg("purchase_sign.owned.line4", "time" to time)
    }

    private fun missing(sign: PurchaseSign): List<Component> = listOf(
        lang.msg("purchase_sign.missing.line1"),
        lang.msg("purchase_sign.missing.line2", "stall" to sign.stallId.value),
        Component.empty(),
        Component.empty(),
    )

    /**
     * For stalls created before V011 the `next_rent_at` column is null;
     * estimate from ownerSince + interval so the sign still shows a
     * timer until the next collection tick fills the column in.
     */
    private fun fallbackNextRent(stall: Stall): Instant {
        val owner = stall.ownerSince ?: Instant.now()
        return owner.plus(collectionInterval())
    }

    private fun collectionInterval(): Duration = try {
        Duration.parse(config.rent.collectionInterval)
    } catch (_: java.time.format.DateTimeParseException) {
        Duration.ofDays(1)
    }
}
