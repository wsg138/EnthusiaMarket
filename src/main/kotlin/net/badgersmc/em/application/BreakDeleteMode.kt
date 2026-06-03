package net.badgersmc.em.application

import net.badgersmc.nexus.annotations.Component
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Ephemeral per-player "break to delete" mode (ItemShops parity). While active,
 * the BlockProtectionListener lets the owner break their own shop sign and
 * deletes the shop. Not persisted — clears on restart, exactly like ItemShops.
 */
@Component
class BreakDeleteMode {

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
            expiry.remove(player)
            return false
        }
        return true
    }

    companion object {
        /** Parse the ItemShops-style arg: null=off, "on"/absent=5m, "Nm"=N minutes, garbage=5m. */
        fun parseDurationMs(arg: String?): Long? {
            val a = arg?.lowercase()?.trim()
            if (a == "off") return null
            if (a == null || a == "on" || a.isEmpty()) return DEFAULT_MS
            if (a.endsWith("m")) {
                val mins = a.dropLast(1).toLongOrNull()
                if (mins != null && mins > 0) return mins * 60_000
            }
            return DEFAULT_MS
        }

        private const val DEFAULT_MS = 5L * 60_000
    }
}