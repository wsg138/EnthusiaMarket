package net.badgersmc.em.application

import net.badgersmc.nexus.annotations.Component
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Ephemeral per-admin "break any shop to delete" mode (ItemShops parity SP5).
 * While active, BlockProtectionListener lets an admin break ANY shop sign and
 * deletes that shop. Not persisted — clears on restart, exactly like the
 * owner-scoped BreakDeleteMode it mirrors. Reuses BreakDeleteMode.parseDurationMs
 * for the arg shape.
 */
@Component
class AdminBreakMode {

    private val expiry = ConcurrentHashMap<UUID, Long>()

    fun enable(player: UUID, durationMs: Long, nowMs: Long = System.currentTimeMillis()) {
        expiry[player] = nowMs + durationMs
    }

    fun disable(player: UUID) {
        expiry.remove(player)
    }

    fun isActive(player: UUID, nowMs: Long = System.currentTimeMillis()): Boolean {
        val until = expiry[player] ?: return false
        if (nowMs > until) {
            // Remove only if still the same expiry we read, so a concurrent enable()
            // that refreshed the window isn't clobbered by this lazy cleanup.
            expiry.remove(player, until)
            return false
        }
        return true
    }
}