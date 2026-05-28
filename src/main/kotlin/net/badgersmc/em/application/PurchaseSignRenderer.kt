package net.badgersmc.em.application

import net.badgersmc.em.config.EnthusiaMarketConfig
import net.badgersmc.em.domain.auction.AuctionRepository
import net.badgersmc.em.domain.sign.PurchaseSign
import net.badgersmc.em.domain.stall.Stall
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

    fun render(sign: PurchaseSign): List<Component> {
        val stall = stalls.findById(sign.stallId)
            ?: return missing(sign)

        return when (stall.state) {
            StallState.UNOWNED -> buyable(sign)
            StallState.AUCTIONING, StallState.RE_AUCTIONING, StallState.EMERGENCY_AUCTIONING ->
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
        val auction = auctions.findOpenByStall(sign.stallId)
        val currentBid = auction?.highBid?.amount ?: auction?.startingBid ?: 0L
        return listOf(
            lang.msg("purchase_sign.auction.line1"),
            lang.msg("purchase_sign.auction.line2", "stall" to sign.stallId.value),
            lang.msg("purchase_sign.auction.line3", "bid" to currentBid),
            lang.msg("purchase_sign.auction.line4"),
        )
    }

    private fun ownedLines(sign: PurchaseSign, stall: Stall): List<Component> {
        val ownerName = owners.displayNameFor(stall.owner)
        val nextRent = stall.nextRentAt ?: fallbackNextRent(stall)
        val timeLeft = formatTimeLeft(nextRent)
        return listOf(
            lang.msg("purchase_sign.owned.line1"),
            lang.msg("purchase_sign.owned.line2", "owner" to ownerName),
            lang.msg("purchase_sign.owned.line3", "time" to timeLeft),
            lang.msg("purchase_sign.owned.line4"),
        )
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

    /**
     * Compact "<n><u>" formatting: 23h, 47m, 12s, overdue. The sign
     * line has ~15 chars after the colour codes so brevity matters.
     */
    private fun formatTimeLeft(target: Instant): String {
        val remaining = Duration.between(Instant.now(), target)
        if (remaining.isZero || remaining.isNegative) return lang.msg("purchase_sign.time.overdue")
        val days = remaining.toDays()
        if (days > 0) return "${days}d"
        val hours = remaining.toHours()
        if (hours > 0) return "${hours}h"
        val minutes = remaining.toMinutes()
        if (minutes > 0) return "${minutes}m"
        return "${remaining.seconds}s"
    }
}
