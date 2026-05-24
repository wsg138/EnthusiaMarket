package net.badgersmc.em

import net.badgersmc.em.config.EnthusiaMarketConfig
import net.badgersmc.em.domain.stall.RentTerms
import net.badgersmc.em.infrastructure.persistence.Database
import net.badgersmc.em.infrastructure.persistence.Migrations
import net.badgersmc.nexus.core.NexusContext
import net.badgersmc.nexus.paper.registerPaperCommands
import org.bukkit.plugin.java.JavaPlugin
import javax.sql.DataSource

open class EnthusiaMarket : JavaPlugin() {

    private var nexus: NexusContext? = null

    override fun onEnable() {
        dataFolder.mkdirs()

        // Phase 1: Create Nexus DI context — generates enthusiamarket.yaml with defaults
        // if it doesn't exist yet (ConfigLoader handles this).
        nexus = NexusContext.create(
            basePackage = "net.badgersmc.em",
            classLoader = this::class.java.classLoader,
            configDirectory = dataFolder.toPath(),
            contextName = "EnthusiaMarket",
            externalBeans = mapOf("plugin" to this)
        )

        // Phase 2: Read config from Nexus for database bootstrap
        val cfg = nexus!!.getBean<EnthusiaMarketConfig>()
        val ds = Database.open(
            type = cfg.database.type,
            sqliteFile = cfg.database.sqliteFile,
            dataFolder = dataFolder,
            mariadbHost = cfg.database.mariadb.host,
            mariadbPort = cfg.database.mariadb.port,
            mariadbDatabase = cfg.database.mariadb.database,
            mariadbUsername = cfg.database.mariadb.username,
            mariadbPassword = cfg.database.mariadb.password
        )
        Migrations.runAll(ds)

        // Phase 3: Register DataSource bean so @Repository classes can resolve it
        nexus!!.registerBean("dataSource", DataSource::class, ds as DataSource)

        // Register defaultRent from config
        val defaultRent = if (cfg.rent.mode == "flat") {
            RentTerms.flat(cfg.rent.flatAmount)
        } else {
            RentTerms.formula(cfg.rent.formulaPct)
        }
        nexus!!.registerBean("defaultRent", RentTerms::class, defaultRent)

        // Phase 4: Register Paper commands (triggers bean creation via DI)
        nexus!!.registerPaperCommands(
            basePackage = "net.badgersmc.em",
            classLoader = this::class.java.classLoader,
            plugin = this
        )

        logger.info("EnthusiaMarket enabled (v${description.version})")
    }

    override fun onDisable() {
        nexus?.close()
        logger.info("EnthusiaMarket disabled")
    }
}