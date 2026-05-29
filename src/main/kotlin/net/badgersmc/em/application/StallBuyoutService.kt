package net.badgersmc.em.application

import net.badgersmc.em.config.EnthusiaMarketConfig
import net.badgersmc.em.domain.offer.SellOfferRepository
import net.badgersmc.em.domain.ports.EconomyProvider
import net.badgersmc.em.domain.ports.GuildProvider
import net.badgersmc.em.domain.ports.RegionMemberSync
import net.badgersmc.em.domain.stall.OwnerRef
import net.badgersmc.em.domain.stall.OwnerType
import net.badgersmc.em.domain.stall.Stall
import net.badgersmc.em.domain.stall.StallId
import net.badgersmc.em.domain.stall.StallRepository
import net.badgersmc.em.domain.stall.StallState
import net.badgersmc.em.events.StallStateChangedEvent
import net.badgersmc.nexus.annotations.Service
import org.bukkit.Bukkit
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.logging.Logger

/**
 * Orchestrates outright "click-the-sign-to-buy" stall purchases (REQ-250).
 *
 * Used by [net.badgersmc.em.infrastructure.listeners.PurchaseSignClickListener]
 * to convert a buyer + price + UNOWNED stall into ownership. Designed
 * for the post-initial-auction lifecycle: once the one-shot mass
 * auction has run, every remaining or evicted stall becomes buyable
 * via its purchase sign at the price written on line 3.
 *
 * Compensation order matches `AuctionLifecycleService.settleWithWinner`
 * and `SellOfferService.purchase`: withdraw → persist → fire event.
 * If withdraw fails the buyer is untouched and no state moves.
 */
@Service
class StallBuyoutService(
    private val stalls: StallRepository,
    private val offers: SellOfferRepository,
    private val auctions: net.badgersmc.em.domain.auction.AuctionRepository,
    private val economy: EconomyProvider,
    private val config: EnthusiaMarketConfig,
    private val guildProvider: GuildProvider,
    private val regionMembers: RegionMemberSync,
) {

    private val log = Logger.getLogger(StallBuyoutService::class.java.name)

    sealed interface Result {
        data class Purchased(val stall: Stall, val price: Long, val owner: OwnerRef) : Result
        data object NotFound : Result
        data object AuctionLive : Result
        data object AlreadyOwned : Result
        data object NotInGuild : Result
        data object NoGuildPermission : Result
        data class Rejected(val reason: String) : Result
    }

    /**
     * Buy the stall for [buyer] personally. Charges + awards to a SOLO
     * owner ref. Convenience overload over [buyForOwner].
     */
    fun buy(stallId: StallId, buyer: UUID, price: Long): Result =
        buyForOwner(stallId, payer = buyer, owner = OwnerRef.solo(buyer), price = price)

    /**
     * Buy the stall on behalf of [actor]'s current guild. [actor] is
     * charged personally (the guild bank isn't a UUID-addressable
     * economy account in the current Vault setup); the stall is
     * awarded to OwnerRef.guild. Requires the actor to be a guild
     * member with the MANAGE_SHOPS permission so randoms can't bind
     * a stall to a guild they don't have authority over.
     */
    fun buyForGuild(stallId: StallId, actor: UUID, price: Long): Result {
        val guild = guildProvider.guildOf(actor) ?: return Result.NotInGuild
        if (!guildProvider.hasShopPermission(
                actor,
                guild.id,
                GuildProvider.GuildPermission.MANAGE_SHOPS,
            )
        ) {
            return Result.NoGuildPermission
        }
        // WG sync for guilds not yet wired — attempt best-effort,
        // but don't block the purchase. DB is authoritative; /em rg resync can fix WG.
        val result = buyForOwner(stallId, payer = actor, owner = OwnerRef.guild(guild.id), price = price)
        if (result is Result.Purchased) {
            try {
                regionMembers.setOwner(result.stall.world, result.stall.regionId, java.util.UUID.fromString(result.stall.owner.id))
            } catch (e: Exception) {
                log.warning(
                    "StallBuyoutService: WG owner sync failed for guild stall ${stallId.value}; " +
                        "DB owner is correct. cause=${e.message}"
                )
            }
        }
        return result
    }

    private fun buyForOwner(stallId: StallId, payer: UUID, owner: OwnerRef, price: Long): Result {
        if (price <= 0) return Result.Rejected("Sign price is invalid")

        val stall = stalls.findById(stallId) ?: return Result.NotFound

        // Reject on AUCTIONING — the one-shot initial auction is using the
        // stall right now; click-to-buy is for the post-auction lifecycle.
        if (stall.state in setOf(
                StallState.AUCTIONING,
                StallState.RE_AUCTIONING,
                StallState.EMERGENCY_AUCTIONING,
            ) ||
            auctions.findOpenByStall(stallId) != null
        ) {
            return Result.AuctionLive
        }

        if (stall.state != StallState.UNOWNED) {
            return Result.AlreadyOwned
        }

        if (!economy.withdraw(payer, price)) {
            return Result.Rejected("Insufficient funds: $price required")
        }

        val previousState = stall.state
        val updated = try {
            val now = Instant.now()
            val awarded = stall.awardTo(owner, price, now)
                .copy(nextRentAt = now.plus(collectionInterval()))
            stalls.save(awarded)
            // Defensive: if a sell offer somehow lingered on an UNOWNED
            // stall, clean it up so a follow-up click doesn't trip the
            // mutex check next door.
            if (offers.findByStall(stallId) != null) {
                try {
                    offers.delete(stallId)
                } catch (cleanupErr: Exception) {
                    log.warning(
                        "StallBuyoutService: failed to cleanup lingering sell offer for " +
                            "${stallId.value}. cause=${cleanupErr.message}"
                    )
                }
            }
            awarded
        } catch (e: Exception) {
            log.severe(
                "StallBuyoutService: ownership transfer failed for stall " +
                    "${stallId.value} after charging payer $payer price=$price " +
                    "(owner=$owner). Manual refund required. cause=${e.message}"
            )
            throw e
        }

        // Sync ownership to WorldGuard so the new owner can actually
        // build / break / interact inside the region without being op.
        // SOLO → WG owner = buyer UUID. GUILD → can't map a guild to
        // a WG player UUID directly; log + skip. Operators can wire
        // a LumaGuilds → WG bridge later. Failures are logged but
        // don't roll back the purchase — the DB owner remains the
        // canonical source of truth and a resync command can be added.
        try {
            when (owner.type) {
                OwnerType.SOLO -> regionMembers.setOwner(
                    updated.world, updated.regionId, java.util.UUID.fromString(owner.id)
                )
                OwnerType.GUILD -> log.warning(
                    "StallBuyoutService: stall ${stallId.value} awarded to guild ${owner.id} " +
                        "but WG owner mapping for guilds isn't wired — guild members may need " +
                        "op or explicit /em stall members add to build until a bridge ships."
                )
                OwnerType.NONE -> Unit // unreachable; awardTo rejects NONE.
            }
        } catch (e: Exception) {
            log.warning(
                "StallBuyoutService: WG owner sync failed for stall ${stallId.value} " +
                    "(owner=$owner). The DB owner is correct; players may need op until " +
                    "the region is resynced. cause=${e.message}"
            )
        }

        fireStateChanged(stallId.value, previousState, updated.state)
        return Result.Purchased(updated, price, owner)
    }

    private fun collectionInterval(): Duration = try {
        Duration.parse(config.rent.collectionInterval)
    } catch (_: java.time.format.DateTimeParseException) {
        Duration.ofDays(1)
    }

    private fun fireStateChanged(
        stallId: String,
        previous: StallState,
        current: StallState,
    ) {
        if (previous == current) return
        try {
            Bukkit.getServer()?.pluginManager?.callEvent(
                StallStateChangedEvent(stallId, previous, current)
            )
        } catch (e: Exception) {
            log.warning("Failed to fire StallStateChangedEvent for $stallId: ${e.message}")
        }
    }
}
