package net.badgersmc.em

import net.badgersmc.em.application.ItemStackSerializer
import net.badgersmc.em.config.EnthusiaMarketConfig
import net.badgersmc.em.domain.shop.Shop
import net.badgersmc.em.domain.shop.ShopRepository
import net.badgersmc.em.infrastructure.i18n.EnthusiaMarketLang
import net.badgersmc.em.infrastructure.listeners.SignPlaceListener
import net.badgersmc.em.infrastructure.scheduler.AuctionScheduler
import net.badgersmc.em.infrastructure.scheduler.RentScheduler
import net.badgersmc.em.infrastructure.scheduler.ShopAuditScheduler
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

    @Suppress("LongMethod", "TooGenericExceptionThrown")
    override fun onEnable() {
        dataFolder.mkdirs()

        // Register our permissions in code. Paper/Leaf does not reliably register
        // the `permissions:` block from paper-plugin.yml for this plugin (custom
        // loader + Paper plugin format), so on a server with no permission manager
        // (vanilla SuperPerms) every default-true node silently resolves to op-only
        // — which makes Brigadier hide every /em and /shop subcommand from non-ops
        // ("Unknown command"). Reading the same generated descriptor keeps the
        // nexus-permissions DSL the single source of truth (no drift). Idempotent.
        registerDeclaredPermissions()

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

        // Nexus indexes external beans by concrete class + superclasses, NOT
        // interfaces — so a bean injected as `JavaPlugin` (superclass) resolves
        // but one injected as `org.bukkit.plugin.Plugin` (interface) does not.
        // Register the plugin explicitly under the Plugin interface so beans
        // that depend on `Plugin` (WorldEditSchematicAdapter, EntityLimitListener,
        // RentScheduler, AuctionScheduler) can be constructed.
        ctx.registerBean("bukkitPlugin", org.bukkit.plugin.Plugin::class, this)

        // Phase 3: Read config from Nexus for database + i18n + scheduler bootstrap.
        val cfg = ctx.getBean<EnthusiaMarketConfig>()

        // Ensure the schematic snapshot directory exists so the first capture
        // never fails on a missing folder (REQ-270, INFRA-20).
        val schematicsDir = File(dataFolder, cfg.schematics.directory)
        if (!schematicsDir.exists() && !schematicsDir.mkdirs()) {
            logger.warning("Failed to create schematics directory: ${schematicsDir.absolutePath}")
        }
        // Ship the default entity-limit groups (REQ-220) on first run so
        // operators have a template to edit; never overwrite local edits.
        if (!File(dataFolder, "entitylimits.yml").exists()) {
            saveResource("entitylimits.yml", false)
        }

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

        // Shop repository + in-memory container index (REQ-281/282, PERF-4). The hopper-control
        // hot path (InventoryMoveItemEvent) must resolve shop status without a DB query, so we wrap
        // the SQL repo in IndexedShopRepository and register THAT as the sole ShopRepository bean.
        // ShopRepositorySql is no longer @Repository (would be a second bean under the interface →
        // ambiguous DI), so it is built here explicitly. Must run before registerPaperCommands /
        // registerNexusListeners, which trigger construction of every ShopRepository consumer.
        val shopLocationIndex = net.badgersmc.em.application.InMemoryShopLocationIndex()
        val shopSqlRepo = net.badgersmc.em.infrastructure.persistence.ShopRepositorySql(ds)
        shopLocationIndex.rebuild(shopSqlRepo.all()) // REQ-282: rebuild from persistence on enable
        val shopRepository: ShopRepository =
            net.badgersmc.em.application.IndexedShopRepository(shopSqlRepo, shopLocationIndex)
        ctx.registerBean(
            "shopLocationIndex",
            net.badgersmc.em.domain.shop.ShopLocationIndex::class,
            shopLocationIndex,
        )
        ctx.registerBean("shopRepository", ShopRepository::class, shopRepository)

        // NexusScheduler — replaces ad-hoc Bukkit.getScheduler() calls; auto-cancels on disable.
        val sched = NexusScheduler(this)
        ctx.registerBean("nexusScheduler", NexusScheduler::class, sched)
        scheduler = sched

        // Provisioning priority for stall regions (REQ Workstream F) — a
        // plain Int bean so ImportStallsService can be DI-constructed.
        ctx.registerBean("stallPriority", Int::class, cfg.market.stallPriority)

        // Phase 5: Register Paper commands (triggers bean creation via DI).
        // itemMaterials suggestion provider backs `/shop search` tab-completion (REQ-283):
        // computed once here, prefix-filtered per keystroke by the pure MaterialSuggestions helper.
        val itemMaterialNames = org.bukkit.Material.entries.filter { it.isItem }.map { it.name }
        ctx.registerPaperCommands(
            basePackage = "net.badgersmc.em",
            classLoader = this::class.java.classLoader,
            plugin = this,
            suggestionProviders = mapOf(
                "itemMaterials" to com.mojang.brigadier.suggestion.SuggestionProvider { _, builder ->
                    net.badgersmc.em.application.MaterialSuggestions
                        .matching(itemMaterialNames, builder.remaining)
                        .forEach(builder::suggest)
                    builder.buildFuture()
                },
            ),
        )

        // Phase 6: Discover every @Listener-annotated bean in the scan
        // package, resolve it from DI, and register it with Bukkit.
        // NOTE: registerNexusListeners is fail-OPEN per listener — a bean
        // whose dependencies can't resolve is logged as a WARNING and
        // skipped, not rethrown. The outer catch only guards scan-level
        // failures. Watch the boot log's "Registered N @Listener beans"
        // count when adding listeners.
        try {
            net.badgersmc.nexus.paper.listeners.registerNexusListeners(
                basePackage = "net.badgersmc.em",
                classLoader = this::class.java.classLoader,
                plugin = this,
                nexus = ctx,
            )
        } catch (e: Exception) {
            throw RuntimeException("Failed to register listeners — disabling plugin. ${e.message}", e)
        }

        // Phase 6.5: Eagerly construct the schedulers. Nexus DI is lazy —
        // nothing else depends on these beans, so without an explicit
        // getBean their @PostConstruct start() never runs and rent
        // collection + auction settlement silently never happen
        // (audit 2026-06-09, W-2). Guarded by ListenerWiringTest.
        ctx.getBean<RentScheduler>()
        ctx.getBean<AuctionScheduler>()
        ctx.getBean<ShopAuditScheduler>()

        // PlaceholderAPI expansions (no-ops if PAPI absent).
        net.badgersmc.nexus.papi.registerNexusExpansions(
            basePackage = "net.badgersmc.em",
            classLoader = this::class.java.classLoader,
            nexus = ctx,
        )

        // Prune old shop transaction history per config (0 = keep everything).
        if (cfg.shop.historyRetentionDays > 0) {
            val txRepo = ctx.getBean<net.badgersmc.em.domain.shop.ShopTransactionRepository>()
            val cutoff = System.currentTimeMillis() - cfg.shop.historyRetentionDays.toLong() * 86_400_000L
            val pruned = txRepo.prune(cutoff)
            if (pruned > 0) logger.info("Pruned $pruned old shop transaction(s)")
        }

        // M-16: on guild disband, free its stalls + unbind its shops.
        val guildDissolution = ctx.getBean<net.badgersmc.em.application.GuildDissolutionService>()
        ctx.getBean<net.badgersmc.em.domain.ports.GuildProvider>().onDissolved { guildId ->
            guildDissolution.handle(guildId)
        }

        // Particle outline render loop (REQ-240/241): every 4 ticks, plan
        // points within the global budget and spawn END_ROD per requesting
        // player. Purges expired outlines first.
        val particleService = ctx.getBean<net.badgersmc.em.application.ParticleBorderService>()
        org.bukkit.Bukkit.getScheduler().runTaskTimer(this, Runnable {
            particleService.purgeExpired(java.time.Instant.now())
            particleService.renderTick(cfg.particles.maxPerTick, this)
        }, 0L, 4L)

        // REQ-287: re-render purchase signs in loaded chunks every 20t so the OWNED rent
        // countdown ticks down visibly instead of freezing between state changes.
        val signRefresh = ctx.getBean<net.badgersmc.em.infrastructure.listeners.PurchaseSignRefreshListener>()
        object : org.bukkit.scheduler.BukkitRunnable() {
            override fun run() {
                signRefresh.refreshLoaded()
            }
        }.runTaskTimer(this, 20L, 20L)

        // Container stock sign refresh every 20 ticks — catches stock drift from shift-click,
        // hopper, or other-plugin inventory mutations. Only touches loaded chunks; deduplicates
        // via lastRawStock so signs and DB are only touched when the count actually changes.
        val stockRefresh = ctx.getBean<net.badgersmc.em.infrastructure.listeners.ContainerStockListener>()
        Bukkit.getScheduler().runTaskTimer(this, Runnable {
            stockRefresh.refreshAllSigns()
        }, 20L, 20L)

        // M-20: one-time backfill of sell_material for shops written before V018.
        // Off the main thread + fail-open so a large table or a DB hiccup can't stall boot.
        val shopRepoForBackfill = ctx.getBean<net.badgersmc.em.domain.shop.ShopRepository>()
        Bukkit.getScheduler().runTaskAsynchronously(this, Runnable {
            try {
                val backfilled = shopRepoForBackfill.backfillSellMaterials()
                if (backfilled > 0) logger.info("Backfilled sell_material for $backfilled shop(s)")
            } catch (e: Exception) {
                logger.warning("sell_material backfill failed (search may be incomplete until next boot): ${e.message}")
            }
        })

        // M-19: one-time best-effort backfill of stock_count for shops written before V019.
        scheduleStockBackfill(shopRepoForBackfill)

        logger.info("EnthusiaMarket enabled (v${description.version})")
    }

    /**
     * Schedule the one-time stock_count backfill on the NEXT tick. Block state is
     * main-thread-only, so this is NOT async; it only touches shops whose container chunk
     * is already loaded (never force-loads). Fail-open: a failure only means /shop search
     * shows a stale count for some shops until they're next edited/traded or the server reboots.
     */
    private fun scheduleStockBackfill(repo: ShopRepository) {
        Bukkit.getScheduler().runTaskLater(this, Runnable {
            try {
                val n = backfillStockCount(repo)
                if (n > 0) logger.info("Backfilled stock_count for $n loaded shop(s)")
            } catch (e: Exception) {
                logger.warning("stock_count backfill failed: ${e.message}")
            }
        }, 1L)
    }

    /**
     * Recompute + persist the denormalized stock_count for every shop whose container chunk
     * is currently loaded. Main-thread only (reads block state); never force-loads a chunk —
     * shops in unloaded chunks keep their last-persisted count and refresh on the next
     * container edit/trade. Returns the number of shops updated.
     */
    private fun backfillStockCount(repo: ShopRepository): Int {
        var updated = 0
        for (shop in repo.all()) {
            val stock = stockIfLoaded(shop) ?: continue
            repo.updateStock(shop.id, stock)
            updated++
        }
        return updated
    }

    /** Raw container stock for [shop], or null if its container chunk isn't loaded (no force-load). */
    private fun stockIfLoaded(shop: Shop): Int? {
        val container = loadedContainer(shop) ?: return null
        val sellStack = ItemStackSerializer.deserialize(shop.sellItem) ?: return null
        return container.inventory.contents.filterNotNull()
            .filter { it.isSimilar(sellStack) }
            .sumOf { it.amount }
    }

    /** The shop's container block state, only if its chunk is already loaded; null otherwise. */
    private fun loadedContainer(shop: Shop): org.bukkit.block.Container? {
        val world = Bukkit.getWorld(shop.containerWorld) ?: return null
        if (!world.isChunkLoaded(shop.containerX shr 4, shop.containerZ shr 4)) return null
        return world.getBlockAt(shop.containerX, shop.containerY, shop.containerZ).state
            as? org.bukkit.block.Container
    }

    /**
     * Register every permission declared in the bundled `paper-plugin.yml`
     * `permissions:` block with Bukkit, with its declared default. This is the
     * runtime registration Paper/Leaf isn't performing for us; without it,
     * default-true player nodes resolve to op-only on a server with no permission
     * manager and Brigadier hides the commands from non-ops. Idempotent — skips
     * nodes already present so it never clobbers an externally-managed perm.
     */
    private fun registerDeclaredPermissions() {
        val perms = loadDeclaredPermissions() ?: return
        val pm = Bukkit.getPluginManager()
        val registered = perms.getKeys(false).count { registerPermissionNode(pm, perms, it) }
        logger.info("Registered $registered EnthusiaMarket permissions")
    }

    /**
     * Parse the bundled `paper-plugin.yml` `permissions:` section. The path separator
     * is set to `/` so dotted node names (e.g. `enthusiamarket.shop.use`) aren't split
     * by `getString("$node/default")` — splitting on `.` reads a missing path and
     * silently defaults every perm to OP. Null on any IO/parse failure.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun loadDeclaredPermissions(): org.bukkit.configuration.ConfigurationSection? {
        val stream = this::class.java.classLoader.getResourceAsStream("paper-plugin.yml") ?: run {
            logger.warning("paper-plugin.yml not found on classpath; skipping permission registration")
            return null
        }
        return try {
            val yaml = org.bukkit.configuration.file.YamlConfiguration()
            yaml.options().pathSeparator('/')
            yaml.load(java.io.InputStreamReader(stream, Charsets.UTF_8))
            yaml.getConfigurationSection("permissions")
        } catch (e: Exception) {
            logger.warning("Failed to read declared permissions: ${e.message}")
            null
        }
    }

    /** Register [node] with its declared default if Bukkit doesn't already have it. Returns true when added. */
    private fun registerPermissionNode(
        pm: org.bukkit.plugin.PluginManager,
        perms: org.bukkit.configuration.ConfigurationSection,
        node: String,
    ): Boolean {
        if (pm.getPermission(node) != null) return false
        return try {
            pm.addPermission(org.bukkit.permissions.Permission(node, permissionDefault(perms.getString("$node/default"))))
            true
        } catch (e: IllegalArgumentException) {
            false // registered concurrently between the check and the add
        }
    }

    private fun permissionDefault(token: String?): org.bukkit.permissions.PermissionDefault =
        when (token?.lowercase()) {
            "true" -> org.bukkit.permissions.PermissionDefault.TRUE
            "false" -> org.bukkit.permissions.PermissionDefault.FALSE
            "not op", "notop", "!op" -> org.bukkit.permissions.PermissionDefault.NOT_OP
            else -> org.bukkit.permissions.PermissionDefault.OP
        }

    override fun onDisable() {
        scheduler?.cancelAll()
        nexus?.close()
        logger.info("EnthusiaMarket disabled")
    }
}
