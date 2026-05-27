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

    class Market {
        @Comment("World name where stall regions exist")
        var world: String = "world"
        @Comment("WorldGuard region prefix for stall detection")
        var regionPrefix: String = "stall_"
    }

    class Rent {
        @Comment("Rent mode: formula or flat")
        var mode: String = "formula"
        @Comment("Percentage of winning bid per period (used when mode=formula)")
        var formulaPct: Double = 0.01
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