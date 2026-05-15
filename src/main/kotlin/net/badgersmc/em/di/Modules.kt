package net.badgersmc.em.di

import com.zaxxer.hikari.HikariDataSource
import net.badgersmc.em.application.ImportStallsService
import net.badgersmc.em.domain.ports.EconomyProvider
import net.badgersmc.em.domain.ports.GuildProvider
import net.badgersmc.em.domain.ports.RegionProvider
import net.badgersmc.em.domain.stall.RentTerms
import net.badgersmc.em.domain.stall.StallRepository
import net.badgersmc.em.infrastructure.lumaguilds.LumaGuildsGuildProvider
import net.badgersmc.em.infrastructure.persistence.StallRepositorySql
import net.badgersmc.em.infrastructure.vault.VaultEconomyProvider
import net.badgersmc.em.infrastructure.worldguard.WorldGuardRegionProvider
import net.milkbowl.vault.economy.Economy
import org.bukkit.Bukkit
import org.koin.dsl.module
import javax.sql.DataSource

/**
 * Vault may be absent in test environments (e.g. MockBukkit). The economy
 * binding is therefore resolved on first use; foundation features never
 * resolve it, so absence is silent until a later plan actually needs it.
 */
private class LazyEconomyProvider : EconomyProvider {
    private val delegate: EconomyProvider by lazy {
        val rsp = Bukkit.getServicesManager().getRegistration(Economy::class.java)
            ?: error("Vault Economy not registered - required for economy operations")
        VaultEconomyProvider(Bukkit.getServer(), rsp.provider)
    }
    override fun balance(player: java.util.UUID) = delegate.balance(player)
    override fun withdraw(player: java.util.UUID, amount: Long) = delegate.withdraw(player, amount)
    override fun deposit(player: java.util.UUID, amount: Long) = delegate.deposit(player, amount)
}

fun emModule(ds: HikariDataSource, defaultRent: RentTerms) = module {
    single<DataSource> { ds }
    single<StallRepository> { StallRepositorySql(get()) }
    single<RegionProvider> { WorldGuardRegionProvider() }
    single<EconomyProvider> { LazyEconomyProvider() }
    single<GuildProvider> { LumaGuildsGuildProvider() }
    single { ImportStallsService(get(), get(), defaultRent) }
}
