package net.badgersmc.em.infrastructure.persistence

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import net.badgersmc.em.domain.guild.GuildTradePolicy
import net.badgersmc.em.domain.guild.PolicyKind
import net.badgersmc.nexus.persistence.MigrationRunner
import javax.sql.DataSource
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GuildTradePolicyRepositorySqlTest {
    private lateinit var ds: DataSource
    private lateinit var repo: GuildTradePolicyRepositorySql

    @BeforeTest fun setup() {
        val cfg = HikariConfig().apply { jdbcUrl = "jdbc:sqlite::memory:"; maximumPoolSize = 1 }
        ds = HikariDataSource(cfg)
        MigrationRunner(ds, resourcePrefix = "migrations", classLoader = javaClass.classLoader).runAll()
        repo = GuildTradePolicyRepositorySql(ds)
    }
    @AfterTest fun teardown() { (ds as HikariDataSource).close() }

    @Test fun `upsert then find round-trips a tariff`() {
        repo.upsert(GuildTradePolicy("g1", "g2", PolicyKind.TARIFF, 25))
        val p = repo.find("g1", "g2")!!
        assertEquals(PolicyKind.TARIFF, p.kind); assertEquals(25, p.ratePct)
    }
    @Test fun `upsert overwrites existing pair`() {
        repo.upsert(GuildTradePolicy("g1", "g2", PolicyKind.TARIFF, 10))
        repo.upsert(GuildTradePolicy("g1", "g2", PolicyKind.EMBARGO, 0))
        assertEquals(PolicyKind.EMBARGO, repo.find("g1", "g2")!!.kind)
    }
    @Test fun `listByOwner returns only that owner's rows`() {
        repo.upsert(GuildTradePolicy("g1", "g2", PolicyKind.TARIFF, 10))
        repo.upsert(GuildTradePolicy("g1", "g3", PolicyKind.EMBARGO, 0))
        repo.upsert(GuildTradePolicy("gX", "g2", PolicyKind.TARIFF, 5))
        assertEquals(2, repo.listByOwner("g1").size)
    }
    @Test fun `delete removes a pair`() {
        repo.upsert(GuildTradePolicy("g1", "g2", PolicyKind.TARIFF, 10))
        repo.delete("g1", "g2")
        assertNull(repo.find("g1", "g2"))
    }
    @Test fun `deleteAllInvolving removes rows as owner and as target`() {
        repo.upsert(GuildTradePolicy("g1", "gD", PolicyKind.TARIFF, 10)) // gD as target
        repo.upsert(GuildTradePolicy("gD", "g2", PolicyKind.EMBARGO, 0)) // gD as owner
        repo.upsert(GuildTradePolicy("g1", "g2", PolicyKind.TARIFF, 5))  // unrelated
        repo.deleteAllInvolving("gD")
        assertNull(repo.find("g1", "gD")); assertNull(repo.find("gD", "g2"))
        assertEquals(1, repo.listByOwner("g1").size) // only g1->g2 remains
    }
}
