package net.badgersmc.em.domain.ports

/**
 * Outbound port that stamps a stall's WorldGuard region with the priority
 * and build/use/interact flags required for members to operate the stall
 * inside a surrounding safezone (market/spawn deny build by default).
 *
 * EM replaces ARM (Advanced Region Market); ARM is what configured the
 * legacy stalls' flags. This port lets EM take over that responsibility
 * so newly-carved and previously-unconfigured stalls become usable on
 * import without manual /rg setup. See spec §3 Workstream F.
 *
 * Implementations live in infrastructure (see WorldGuardRegionProvisioner).
 * Idempotent: re-provisioning an already-correct region is a no-op write.
 */
interface RegionProvisioner {
    /**
     * Set [regionId]'s priority to [priority] and apply the standard stall
     * flag set (build/use/chest-access/block-place/block-break/ride scoped
     * to MEMBERS, use to ALL, plus item-frame-rotation + interact for
     * decoration entities). Silently no-ops when the world or region is
     * not loaded — the import caller logs counts.
     *
     * @return true if the region was found and provisioned, false if the
     *         world/region was missing.
     */
    fun provision(world: String, regionId: String, priority: Int): Boolean
}
