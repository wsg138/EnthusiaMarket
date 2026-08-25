package net.badgersmc.em.infrastructure.listeners

import net.badgersmc.em.application.MaintenanceFreezeService
import net.badgersmc.em.application.PurchaseSignRenderer
import net.badgersmc.em.domain.sign.PurchaseSign
import net.badgersmc.em.domain.sign.PurchaseSignRepository
import net.badgersmc.em.domain.stall.StallId
import net.badgersmc.em.events.StallStateChangedEvent
import net.badgersmc.nexus.annotations.Component
import org.bukkit.Bukkit
import org.bukkit.block.Sign
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.SignChangeEvent

/**
 * Re-renders every purchase sign bound to a stall whenever its state
 * changes (REQ-252). Listens for [StallStateChangedEvent] fired by
 * settlement / sell-offer / rent flows.
 *
 * Bukkit's `Sign#line` API is main-thread only; the listener already
 * runs there for Bukkit events, so no scheduler hop is needed.
 */
@net.badgersmc.nexus.paper.listeners.Listener
@Component
open class PurchaseSignRefreshListener(
    private val signs: PurchaseSignRepository,
    private val renderer: PurchaseSignRenderer,
    private val maintenanceFreeze: MaintenanceFreezeService,
) : Listener {

    /** Cached sign list — invalidated on state changes (sign placement,
     *  auction settlement, eviction, etc). Avoids querying the DB every
     *  60 ticks (~3s) when signs rarely change outside events. */
    private var cachedSigns: List<PurchaseSign>? = null

    @EventHandler
    fun onStallStateChanged(event: StallStateChangedEvent) {
        cachedSigns = null  // invalidate — signs may have been added/removed
        val bound = signs.findByStall(StallId(event.stallId))
        for (sign in bound) {
            refresh(sign)
        }
    }

    /** Purchase signs may be created or destroyed without a stall state
     *  change. Invalidate the cache so the next refresh picks them up. */
    @Suppress("UnusedParameter")
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onSignChange(event: SignChangeEvent) { cachedSigns = null }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        if (event.block.type.name.endsWith("_SIGN")) cachedSigns = null
    }

    /**
     * Re-render every purchase sign whose chunk is currently loaded (REQ-287), so the OWNED rent
     * countdown ticks down visibly instead of freezing between state changes. Called on a fixed
     * timer from onEnable. NEVER force-loads a chunk — signs in unloaded chunks are skipped and
     * refresh naturally on their next state change or when their chunk loads.
     *
     * While a maintenance freeze is active the periodic refresh is skipped entirely — the rent
     * countdown display literally freezes (timestamps are static and shifted forward on unfreeze).
     * Event-driven refreshes ([onStallStateChanged]) are NOT gated, so a rent payment made during
     * the freeze still updates its sign.
     */
    fun refreshLoaded() {
        if (maintenanceFreeze.isFrozen()) return
        renderer.refreshAuctionCache()
        val list = cachedSigns ?: signs.all().also { cachedSigns = it }
        for (sign in list) {
            val world = Bukkit.getWorld(sign.world) ?: continue
            if (!world.isChunkLoaded(sign.x shr 4, sign.z shr 4)) continue
            refresh(sign)
        }
    }

    private fun refresh(sign: PurchaseSign) {
        val world = Bukkit.getWorld(sign.world) ?: return
        val block = world.getBlockAt(sign.x, sign.y, sign.z)
        val state = block.state as? Sign ?: return
        val lines = renderer.render(sign)
        val side = state.getSide(org.bukkit.block.sign.Side.FRONT)
        for (i in 0 until 4) {
            side.line(i, lines.getOrElse(i) { net.kyori.adventure.text.Component.empty() })
        }
        state.update(true, false)
    }
}
