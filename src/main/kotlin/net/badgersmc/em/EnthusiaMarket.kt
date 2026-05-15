package net.badgersmc.em

import co.aikar.commands.PaperCommandManager
import net.badgersmc.em.application.ImportStallsService
import net.badgersmc.em.di.emModule
import net.badgersmc.em.domain.stall.RentTerms
import net.badgersmc.em.domain.stall.StallRepository
import net.badgersmc.em.infrastructure.commands.AdminCommands
import net.badgersmc.em.infrastructure.persistence.Database
import net.badgersmc.em.infrastructure.persistence.Migrations
import org.bukkit.plugin.java.JavaPlugin
import org.koin.core.context.GlobalContext.startKoin
import org.koin.core.context.GlobalContext.stopKoin

open class EnthusiaMarket : JavaPlugin() {

    private var commandManager: PaperCommandManager? = null

    override fun onEnable() {
        saveDefaultConfig()
        val ds = Database.open(config, dataFolder.also { it.mkdirs() })
        Migrations.runAll(ds)

        val defaultRent = RentTerms.formula(config.getDouble("rent.formula-pct", 1.0))
        val world = config.getString("market.world", "world")!!
        val prefix = config.getString("market.region-prefix", "stall_")!!

        val koin = startKoin { modules(emModule(ds, defaultRent)) }.koin

        commandManager = PaperCommandManager(this).also { mgr ->
            mgr.registerCommand(
                AdminCommands(
                    service = koin.get<ImportStallsService>(),
                    stalls = koin.get<StallRepository>(),
                    world = world,
                    prefix = prefix
                )
            )
        }

        logger.info("EnthusiaMarket enabled (v${description.version})")
    }

    override fun onDisable() {
        commandManager?.unregisterCommands()
        stopKoin()
        logger.info("EnthusiaMarket disabled")
    }
}
