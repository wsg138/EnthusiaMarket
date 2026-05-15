package net.badgersmc.em.infrastructure.vault

import net.badgersmc.em.domain.ports.EconomyProvider
import net.milkbowl.vault.economy.Economy
import net.milkbowl.vault.economy.EconomyResponse
import org.bukkit.Server
import java.util.UUID

class VaultEconomyProvider(
    private val server: Server,
    private val economy: Economy
) : EconomyProvider {

    override fun balance(player: UUID): Long =
        economy.getBalance(server.getOfflinePlayer(player)).toLong()

    override fun withdraw(player: UUID, amount: Long): Boolean {
        val r = economy.withdrawPlayer(server.getOfflinePlayer(player), amount.toDouble())
        return r.type == EconomyResponse.ResponseType.SUCCESS
    }

    override fun deposit(player: UUID, amount: Long): Boolean {
        val r = economy.depositPlayer(server.getOfflinePlayer(player), amount.toDouble())
        return r.type == EconomyResponse.ResponseType.SUCCESS
    }
}
