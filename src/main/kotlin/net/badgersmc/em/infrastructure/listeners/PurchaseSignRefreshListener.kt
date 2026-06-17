package net.badgersmc.em.infrastructure.listeners

import net.badgersmc.em.application.PurchaseSignRenderer
import net.badgersmc.em.domain.sign.PurchaseSign
import net.badgersmc.em.domain.sign.PurchaseSignRepository
import net.badgersmc.em.domain.stall.StallId
import net.badgersmc.em.events.StallStateChangedEvent
import net.badgersmc.nexus.annotations.Component
import org.bukkit.Bukkit
import org.bukkit.block.Sign
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener

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
) : Listener {

    @EventHandler
    fun onStallStateChanged(event: StallStateChangedEvent) {
        val bound = signs.findByStall(StallId(event.stallId))
        for (sign in bound) {
            refresh(sign)
        }
    }

    /**
     * Re-render every purchase sign whose chunk is currently loaded (REQ-287), so the OWNED rent
     * countdown ticks down visibly instead of freezing between state changes. Called on a fixed
     * timer from onEnable. NEVER force-loads a chunk — signs in unloaded chunks are skipped and
     * refresh naturally on their next state change or when their chunk loads.
     */
    fun refreshLoaded() {
        for (sign in signs.all()) {
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
