package net.badgersmc.em.application

/** Pure audit decision for a single shop (REQ-294). Infra resolves world/block state; this decides. */
object ShopAuditDecision {
    enum class Decision { KEEP, REMOVE, SKIP }

    /**
     * @param blockObservable  can the container block actually be read right now? This requires the
     *   world AND the container's chunk to be loaded — Paper returns AIR for blocks in unloaded
     *   chunks, so an unloaded chunk must count as "not observable", not as a missing container.
     * @param blockIsContainer  when observable, is the container block still a Container?
     * SKIP when the block isn't observable (NEVER delete — we can't see it). KEEP when the
     * container is present. REMOVE only when the block is observable and is not a container.
     */
    fun evaluate(blockObservable: Boolean, blockIsContainer: Boolean): Decision =
        if (!blockObservable) Decision.SKIP else if (blockIsContainer) Decision.KEEP else Decision.REMOVE
}
