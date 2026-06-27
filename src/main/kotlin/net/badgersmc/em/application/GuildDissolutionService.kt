package net.badgersmc.em.application

import net.badgersmc.em.domain.guild.GuildTradePolicyRepository
import net.badgersmc.em.domain.shop.ShopRepository
import net.badgersmc.em.domain.stall.OwnerType
import net.badgersmc.em.domain.stall.StallId
import net.badgersmc.em.domain.stall.StallRepository
import net.badgersmc.nexus.annotations.Service
import java.util.UUID
import java.util.logging.Logger

/**
 * Guild disband cleanup (M-16). Frees every stall owned by the
 * disbanded guild and unbinds every shop registered to that guild.
 *
 * Best-effort: a single failure (e.g. one stall evicts cleanly but
 * another throws) must not abort the rest of the cleanup. Each item
 * is wrapped in its own try/catch so the operator gets a complete
 * sweep even if some entries are partially broken.
 *
 * Stalls are evicted via [StallEvictionService.evict] so all the
 * usual side-effects (WG region reset, schematic restore, state
 * event) run — we never duplicate the eviction logic here. Stalls
 * that are owned by the guild but in a non-OWNED/GRACE state (e.g.
 * mid-auction) will be left alone by evict, which is the same
 * behaviour as a manual `/em evict` and is acceptable for a
 * dissolve.
 */
@Service
class GuildDissolutionService(
    private val stalls: StallRepository,
    private val eviction: StallEvictionService,
    @Suppress("unused") private val shops: ShopRepository,
    private val policies: GuildTradePolicyRepository,
) {
    private val log = Logger.getLogger(GuildDissolutionService::class.java.name)

    /**
     * Free every stall owned by [guildId] and unbind every shop
     * registered to [guildId].
     *
     * @param guildId The LumaGuilds guild id, as a String. Used
     * verbatim to match `Stall.owner.id` (which is a String) and,
     * when parseable, to look up guild shops.
     */
    @Suppress("TooGenericExceptionCaught")
    fun handle(guildId: String) {
        // 1. Evict every GUILD-owned stall for this guild.
        val matchingStalls = stalls.all().filter {
            it.owner.type == OwnerType.GUILD && it.owner.id == guildId
        }
        var stallsEvicted = 0
        var stallsFailed = 0
        for (stall in matchingStalls) {
            try {
                // Only count stalls actually freed. A guild stall in a non-OWNED/GRACE
                // state (e.g. mid-auction) returns NotOwned and is left as-is, so it must
                // not inflate the summary count.
                if (eviction.evict(stall.id) == StallEvictionService.Result.Evicted) {
                    stallsEvicted++
                }
            } catch (e: Exception) {
                stallsFailed++
                log.warning(
                    "GuildDissolution: evict failed for stall ${stall.id.value} " +
                        "(guild=$guildId): ${e.message}"
                )
            }
        }

        // 2. Try to parse the guild id as a UUID so we can look up
        //    shops. A non-UUID guild id is logged and we skip the
        //    shop-unbind step — stall eviction still ran above.
        val shopsUnbound = unbindShops(guildId)

        // 3. Delete every trade policy involving this guild (both as owner and target).
        try {
            policies.deleteAllInvolving(guildId)
        } catch (e: Exception) {
            log.warning("GuildDissolution: failed to delete trade policies for guild=$guildId: ${e.message}")
        }

        // 4. Summary log so an operator can confirm a disband actually
        //    swept everything.
        log.info(
            "GuildDissolution: guild=$guildId " +
                "stallsEvicted=$stallsEvicted stallsFailed=$stallsFailed " +
                "shopsUnbound=$shopsUnbound"
        )
    }

    @Suppress("TooGenericExceptionCaught")
    private fun unbindShops(guildId: String): Int {
        // Per-shop guild ownership (guild_id/creator_id columns) was removed
        // in favour of stall-level ownership. Guild-owned stalls are already
        // evicted by handleDissolved() above. Shops inside those stalls
        // naturally lose their guild context when the stall is evicted.
        log.info("GuildDissolution: per-shop guild ownership removed — shops are now stall-scoped. Skipping individual shop unbind for guild=$guildId.")
        return 0
    }
}
