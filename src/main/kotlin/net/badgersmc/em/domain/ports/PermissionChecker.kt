package net.badgersmc.em.domain.ports

import java.util.UUID

/**
 * Port abstracting "does this player hold this permission node".
 * Lives in the domain so application services can ask the question
 * without dragging org.bukkit onto their import list. Adapters bridge
 * to Bukkit (online players) and optionally Vault (offline players).
 */
interface PermissionChecker {
    fun has(player: UUID, node: String): Boolean
}
