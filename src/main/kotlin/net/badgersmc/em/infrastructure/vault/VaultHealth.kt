package net.badgersmc.em.infrastructure.vault

import net.badgersmc.nexus.annotations.Component

/**
 * Shared marker for Vault economy availability (REQ-041).
 *
 * Set to `true` in [EnthusiaMarket.onEnable] after verifying Vault is present,
 * left at `false` (default) when Vault is absent. Schedulers and listeners
 * that depend on Vault check this flag before registering Bukkit tasks.
 */
@Component
class VaultHealth {
    var isAvailable: Boolean = false
}