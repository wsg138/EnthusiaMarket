package net.badgersmc.em.config

import net.badgersmc.nexus.config.Comment
import net.badgersmc.nexus.config.ConfigFile

@ConfigFile("enthusiamarket")
@Comment("EnthusiaMarket Configuration")
class EnthusiaMarketConfig {
    @Comment("Binding world for stall regions")
    var market: Market = Market()
    @Comment("Periodic rent charge configuration")
    var rent: Rent = Rent()
    @Comment("Auction settings")
    var auction: Auction = Auction()
    @Comment("Sign shop trade settings")
    var shop: Shop = Shop()
    @Comment("LumaGuilds integration")
    var lumaguilds: LumaGuilds = LumaGuilds()
    @Comment("Database settings")
    var database: Database = Database()
    @Comment("Bedrock/Floodgate settings")
    var bedrock: Bedrock = Bedrock()
    @Comment("Debug logging")
    var debug: Debug = Debug()
    @Comment("Localisation / language file selection")
    var lang: Lang = Lang()
    @Comment("Purchase-sign trigger token + permissions (REQ-250..253)")
    var signs: Signs = Signs()
    @Comment("Stall schematic snapshot/restore on claim/unclaim (REQ-270..274)")
    var schematics: Schematics = Schematics()
    @Comment("Stall boundary particle outline rendering (REQ-220 region kinds)")
    var particles: Particles = Particles()

    class Particles {
        @Comment("Master switch for stall boundary particle outlines.")
        var enabled: Boolean = true
        @Comment(
            "Maximum particle spawns scheduled per server tick across all stalls. " +
                "Caps render cost on busy servers; excess is deferred to later ticks."
        )
        var maxPerTick: Int = 200
    }

    class Schematics {
        @Comment(
            "Master switch. When false, stall transitions occur without geometry " +
                "capture or restore (REQ-273)."
        )
        var enabled: Boolean = true
        @Comment(
            "Directory (under the plugin data folder) where <stallId>.schem files " +
                "are written. Created on enable."
        )
        var directory: String = "schematics"
    }

    class Signs {
        @Comment("First-line token a player writes to register a purchase sign (e.g. [em]).")
        var triggerToken: String = "[em]"
        @Comment(
            "Template used to render a SOLO owner's name on OWNED stall signs. " +
                "Expanded via PlaceholderAPI when loaded; falls back to %player_name% substitution otherwise."
        )
        var ownerNameTemplate: String = "%player_name%"
        @Comment(
            "Template used to render a GUILD owner on OWNED stall signs. " +
                "Tokens (resolved from the LumaGuilds API, not PlaceholderAPI): " +
                "%guild_name%, %guild_tag%, %guild_emoji%, %guild_id%. " +
                "%guild_tag%/%guild_emoji% may contain MiniMessage and render with colour."
        )
        var guildNameTemplate: String = "%guild_name%"
        @Comment(
            "Window (seconds) a player has to click an owned sign a second time to " +
                "confirm the rent-extension payment. Lower = stricter; higher = friendlier."
        )
        var confirmWindowSec: Int = 10
    }
    @Comment(
        "ARM-style ownership limit groups (REQ-210). Players gain a group by holding the " +
            "permission enthusiamarket.limit.<group-name>. Effective limits merge by taking the " +
            "best value per dimension across all granted groups (REQ-211). -1 means unlimited."
    )
    var limits: MutableMap<String, LimitGroup> = mutableMapOf()

    class LimitGroup {
        @Comment("Maximum total stalls a player in this group may own. -1 = unlimited.")
        var total: Int = -1

        @Comment("Per-region-kind caps. Key is the region kind name; value -1 = unlimited.")
        var regionkinds: MutableMap<String, Int> = mutableMapOf()
    }

    class Market {
        @Comment("World name where stall regions exist")
        var world: String = "world"
        @Comment("WorldGuard region prefix for stall detection (production regions are stall1..stall71, no underscore)")
        var regionPrefix: String = "stall"
        @Comment(
            "WorldGuard priority stamped on stall regions during /em import. " +
                "Must exceed the surrounding safezone priority (market=10, spawn=10) " +
                "so stall member build-rights override the safezone deny."
        )
        var stallPriority: Int = 20
    }

    class Rent {
        @Comment("Rent mode: formula or flat")
        var mode: String = "formula"
        @Comment(
            "Percentage of winning bid per period (used when mode=formula). " +
                "Interpreted as percent — 1.0 means 1% per period. The old default of " +
                "0.01 produced ~0 rent on small bids (1000 * 0.01 / 100 = 0.1 → 0L) " +
                "which let players infinite-extend rent for free."
        )
        var formulaPct: Double = 1.0
        @Comment("Flat rent amount per period (used when mode=flat)")
        var flatAmount: Long = 0
        @Comment("ISO-8601 duration between collection ticks")
        var collectionInterval: String = "P1D"
        @Comment("ISO-8601 grace period before eviction")
        var gracePeriod: String = "P3D"
    }

    class Auction {
        @Comment("Default auction duration (ISO-8601)")
        var defaultDuration: String = "PT24H"
        @Comment("Minimum auction duration (ISO-8601)")
        var minDuration: String = "PT15M"
        @Comment("Maximum auction duration (ISO-8601)")
        var maxDuration: String = "P7D"
        @Comment("Anti-snipe window in seconds")
        var antiSnipeSec: Int = 30
        @Comment("Fee percentage deducted from seller (decimal)")
        var feePct: Double = 0.05
        @Comment("Minimum starting bid amount")
        var minStartingBid: Long = 1
    }

    class Shop {
        @Comment("Tax percentage on trades (decimal)")
        var taxPct: Double = 0.02
        @Comment("Allow Bedrock players to edit sign content via form")
        var allowBedrockEdit: Boolean = true
        @Comment(
            "Destination for shop / sell-offer tax. A UUID string deposits the " +
                "tax to that account; any other value (including the default 'system') " +
                "routes to a no-op sink — tax is collected but not paid out."
        )
        var taxDestination: String = "system"
        @Comment("Whether newly-created shops are searchable by default (/shop search).")
        var searchDefault: Boolean = true
        @Comment("Notify shop owners when someone trades at their shop (live if online, summarised on next join).")
        var notifyEnabled: Boolean = true
        @Comment("Days of shop transaction history to keep; 0 disables pruning.")
        var historyRetentionDays: Int = 30
    }

    class LumaGuilds {
        @Comment("Enable LumaGuilds integration")
        var enabled: Boolean = true
        @Comment("Source for guild rent payments: bank or leader")
        var payFrom: String = "bank"
    }

    class Database {
        @Comment("Database type: sqlite or mariadb")
        var type: String = "sqlite"
        @Comment("SQLite filename (relative to plugin data folder)")
        var sqliteFile: String = "enthusiamarket.db"
        @Comment("MariaDB connection settings")
        var mariadb: MariaDB = MariaDB()
        @Comment("Connection pool settings")
        var pool: Pool = Pool()

        class MariaDB {
            var host: String = "localhost"
            var port: Int = 3306
            var database: String = "enthusiamarket"
            var username: String = "em"
            @Comment("Leave empty for no password")
            var password: String = ""
        }

        class Pool {
            @Comment("Maximum pool size (1 for SQLite, 10+ for MariaDB)")
            var maxSize: Int = 10
        }
    }

    class Bedrock {
        @Comment("Force Cumulus forms even without Floodgate (for testing)")
        var forceForms: Boolean = false
        @Comment("Form timeout in seconds")
        var formTimeoutSec: Int = 60
    }

    class Lang {
        @Comment("Locale id; loads lang/<locale>.yml from datafolder (en_US shipped)")
        var locale: String = "en_US"
    }

    class Debug {
        @Comment("Log economy transactions")
        var logEconomy: Boolean = false
        @Comment("Log migration execution")
        var logMigrations: Boolean = true
    }
}