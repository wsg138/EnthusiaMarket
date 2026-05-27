package net.badgersmc.em.infrastructure.bukkit

import net.badgersmc.em.domain.ports.PermissionChecker
import net.badgersmc.nexus.annotations.Component
import org.bukkit.Bukkit
import java.util.UUID

/**
 * Bukkit-backed [PermissionChecker]. Returns true only when the
 * player is online and Bukkit reports the permission. Offline
 * resolution would require Vault or a permissions plugin's offline
 * API; deliberately omitted here — limit enforcement at claim time
 * happens in player-driven flows (commands, auction settlement that
 * notifies the winner) where the player is overwhelmingly online.
 * If they aren't, treating them as if they hold no limit groups is
 * the safe default — the claim is rejected rather than over-granted.
 */
@Component
class BukkitPermissionChecker : PermissionChecker {
    override fun has(player: UUID, node: String): Boolean {
        val online = Bukkit.getPlayer(player) ?: return false
        return online.hasPermission(node)
    }
}
