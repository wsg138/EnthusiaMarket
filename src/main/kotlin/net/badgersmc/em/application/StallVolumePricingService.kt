package net.badgersmc.em.application

import net.badgersmc.em.config.EnthusiaMarketConfig
import net.badgersmc.em.domain.ports.RegionProvider
import net.badgersmc.em.domain.stall.RentTerms
import net.badgersmc.em.domain.stall.StallRepository
import net.badgersmc.nexus.annotations.Service

/**
 * Volume-based dynamic stall pricing.
 *
 * Rent for a stall is derived from its WorldGuard region volume:
 * `rent = baseFlat + volume * perBlock`. Volume is the inclusive block
 * count of the region cuboid (width × height × length). All geometry is
 * read through [RegionProvider] once per operation — never on a per-tick
 * path — and results are snapshotted into the stall's stored rent terms,
 * matching how [ImportStallsService] and [RentTermsResyncService] already
 * persist terms at import/resync time.
 */
@Service
class StallVolumePricingService(
    private val regions: RegionProvider,
    private val stalls: StallRepository,
    private val config: EnthusiaMarketConfig,
) {
    /** One row of the pricing preview/apply output. */
    data class PricingRow(
        val stallId: String,
        val volume: Long,
        val computedRent: Long,
    )

    /** Block volume of the region [id] in [world], or null when geometry is unavailable. */
    fun volumeOf(world: String, id: String): Long? {
        val b = regions.bounds(world, id) ?: return null
        // Convert BEFORE multiplication — Int math overflows at ~2.1B blocks
        // (e.g. a 2048×512×2048 region) into a negative volume.
        return b.width.toLong() * b.height.toLong() * b.length.toLong()
    }

    /** Rent implied by [volume] under the current pricing config. */
    fun rentFor(volume: Long): Long =
        (config.pricing.baseFlat + volume * config.pricing.perBlock).toLong()

    /** True when volume pricing is enabled and [world]/[id] has resolvable geometry. */
    fun isApplicable(world: String, id: String): Boolean =
        config.pricing.enabled && volumeOf(world, id) != null

    /**
     * Per-stall rent terms: volume-computed flat amount when pricing is enabled
     * and geometry resolves, otherwise null (caller falls back to the config
     * default). Used at import so new stalls snapshot volume-based terms.
     */
    fun termsFor(world: String, id: String): RentTerms? {
        if (!config.pricing.enabled) return null
        val volume = volumeOf(world, id) ?: return null
        return RentTerms.flat(rentFor(volume))
    }

    /**
     * Read-only listing: every known stall with its region volume and the rent
     * the current config would compute. Never writes to the repository.
     */
    fun preview(world: String, prefix: String): List<PricingRow> =
        stalls.all()
            .filter { it.world == world && it.regionId.startsWith(prefix) }
            .mapNotNull { stall ->
                volumeOf(stall.world, stall.regionId)?.let { volume ->
                    PricingRow(stall.id.value, volume, rentFor(volume))
                }
            }
            .sortedBy { it.stallId }

    /**
     * Apply volume pricing to every known stall: rewrites each stall's stored
     * rent terms to the computed flat amount. Returns the number changed.
     * No-op when pricing is disabled.
     */
    fun apply(world: String, prefix: String): Int {
        if (!config.pricing.enabled) return 0
        var changed = 0
        for (row in preview(world, prefix)) {
            val stall = stalls.findById(net.badgersmc.em.domain.stall.StallId(row.stallId)) ?: continue
            val target = RentTerms.flat(row.computedRent)
            if (stall.rentTerms != target) {
                stalls.save(stall.copy(rentTerms = target))
                changed++
            }
        }
        return changed
    }
}
