package net.badgersmc.em

import net.badgersmc.em.config.EnthusiaMarketConfig
import net.badgersmc.em.domain.stall.RentTerms
import net.badgersmc.em.infrastructure.i18n.EnthusiaMarketLang
import net.badgersmc.nexus.core.NexusContext
import net.badgersmc.nexus.i18n.LangService
import net.badgersmc.nexus.i18n.Locale
import net.badgersmc.nexus.paper.registerPaperCommands
import net.badgersmc.nexus.persistence.DatabaseFactory
import net.badgersmc.nexus.persistence.DatabaseSpec
import net.badgersmc.nexus.persistence.MigrationRunner
import net.badgersmc.nexus.scheduler.NexusScheduler
import net.badgersmc.nexus.vault.VaultHealth
import net.milkbowl.vault.economy.Economy
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import javax.sql.DataSource

open class EnthusiaMarket : JavaPlugin() {

    private var nexus: NexusContext? = null
    private var scheduler: NexusScheduler? = null

    override fun onEnable() {
        dataFolder.mkdirs()

        // Phase 1: Detect Vault availability before Nexus DI context is created,
        // so that schedulers/listeners see the correct VaultHealth value (REQ-041).
        val vaultHealth = VaultHealth()
        val vaultRsp = Bukkit.getServicesManager().getRegistration(Economy::class.java)
        vaultHealth.isAvailable = vaultRsp != null
        if (vaultRsp == null) {
            logger.severe("Vault Economy not found — rent collection, shop signs, and auctions disabled")
        }

        // Phase 2: Create Nexus DI context — generates enthusiamarket.yaml with defaults
        // if it doesn't exist yet (ConfigLoader handles this).
        val ctx = NexusContext.create(
            basePackage = "net.badgersmc.em",
            classLoader = this::class.java.classLoader,
            configDirectory = dataFolder.toPath(),
            contextName = "EnthusiaMarket",
            externalBeans = mapOf("plugin" to this, "vaultHealth" to vaultHealth)
        )
        nexus = ctx

        // Phase 3: Read config from Nexus for database + i18n + scheduler bootstrap.
        val cfg = ctx.getBean<EnthusiaMarketConfig>()

        // i18n service — wired manually since LangService lives outside the EM scan package.
        val lang = LangService(this, Locale(cfg.lang.locale), EnthusiaMarketLang::class.java)
        ctx.registerBean("langService", LangService::class, lang)

        // Database via nexus-persistence
        val dbSpec = when (cfg.database.type) {
            "mariadb" -> DatabaseSpec.MariaDB(
                host = cfg.database.mariadb.host,
                port = cfg.database.mariadb.port,
                database = cfg.database.mariadb.database,
                username = cfg.database.mariadb.username,
                password = cfg.database.mariadb.password
            )
            else -> DatabaseSpec.Sqlite(File(dataFolder, cfg.database.sqliteFile))
        }
        val ds = DatabaseFactory.open(dbSpec)
        MigrationRunner(ds, resourcePrefix = "migrations", classLoader = this::class.java.classLoader).runAll()
        ctx.registerBean("dataSource", DataSource::class, ds as DataSource)

        // NexusScheduler — replaces ad-hoc Bukkit.getScheduler() calls; auto-cancels on disable.
        val sched = NexusScheduler(this)
        ctx.registerBean("nexusScheduler", NexusScheduler::class, sched)
        scheduler = sched

        // Register defaultRent from config
        val defaultRent = if (cfg.rent.mode == "flat") {
            RentTerms.flat(cfg.rent.flatAmount)
        } else {
            RentTerms.formula(cfg.rent.formulaPct)
        }
        ctx.registerBean("defaultRent", RentTerms::class, defaultRent)

        // Phase 5: Register Paper commands (triggers bean creation via DI)
        ctx.registerPaperCommands(
            basePackage = "net.badgersmc.em",
            classLoader = this::class.java.classLoader,
            plugin = this
        )

        // Phase 6: Discover every @Listener-annotated bean in the scan
        // package, resolve it from DI, and register it with Bukkit.
        // Replaces the per-listener getBean+@PostConstruct dance that
        // broke twice when new listeners weren't pulled by any command.
        try {
            net.badgersmc.nexus.paper.listeners.registerNexusListeners(
                basePackage = "net.badgersmc.em",
                classLoader = this::class.java.classLoader,
                plugin = this,
                nexus = ctx,
            )
        } catch (e: Exception) {
            logger.severe("Failed to register Nexus @Listener beans: ${e.message}")
            e.printStackTrace()
        }

        logger.info("EnthusiaMarket enabled (v${description.version})")
    }

    override fun onDisable() {
        scheduler?.cancelAll()
        nexus?.close()
        logger.info("EnthusiaMarket disabled")
    }
}
