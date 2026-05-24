package net.badgersmc.em.infrastructure.vault

import net.badgersmc.em.domain.ports.EconomyProvider
import net.badgersmc.nexus.annotations.Service
import net.milkbowl.vault.economy.Economy
import org.bukkit.Bukkit
import java.util.UUID

/**
 * Lazy economy provider that resolves Vault on first use.
 * If Vault is absent, [delegate] throws on first call, which is caught
 * by consumers to degrade gracefully (REQ-041).
 */
@Service
class LazyEconomyProvider : EconomyProvider {
    private val delegate: EconomyProvider by lazy {
        val rsp = Bukkit.getServicesManager().getRegistration(Economy::class.java)
            ?: error("Vault Economy not registered — economy operations unavailable")
        VaultEconomyProvider(Bukkit.getServer(), rsp.provider)
    }

    override fun balance(player: UUID): Long = delegate.balance(player)
    override fun withdraw(player: UUID, amount: Long): Boolean = delegate.withdraw(player, amount)
    override fun deposit(player: UUID, amount: Long): Boolean = delegate.deposit(player, amount)
}