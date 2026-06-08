# Guild Trade Policies — PR-A (Economic Engine) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Build the tariff/embargo engine for guild-owned shops — domain value, persistence, resolution service, the `ContainerTradeService` cost hook, and the disband-cleanup tie-in. **No UI** (that's PR-B). Fully TDD.

**Architecture:** A per-guild-pair policy row (`GuildTradePolicy`) in a new table; a `GuildTradePolicyService.stanceFor(...)` that returns either `Embargoed` or `Allowed(factor)`; `ContainerTradeService` multiplies the trade `cost` by that factor (the guild bank — already the trade counterparty — captures the difference) and rejects embargoed buyers. Solo / own-guild / no-policy buyers get factor 1.0 (byte-identical to today).

**Tech Stack:** Kotlin 2.0.0, Nexus DI (`@Service`/`@Repository`), JDBC + `MigrationRunner`, JUnit 5 + MockK, detekt 1.23.8.

**Reference spec:** `docs/superpowers/specs/2026-06-08-guild-trade-policies-design.md`

**Standing rules (every task):**
- Prefix bash with `cd <REPO> &&`. On Hermes' box prefix gradle with `export JAVA_HOME=/opt/data/jdk-21.0.11+10 &&`; repo `/opt/data/EnthusiaMarket`, jar `/opt/data/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar`.
- Every gradle command includes `-Plumaguilds.jar=<JAR> --no-daemon --console=plain`.
- LF→CRLF warnings expected. Branch `feat/guild-trade-policies` off current `main`. Do not push to BadgersMC (coordinator opens the PR; Hermes pushes to `fork` only).
- TDD: failing test first, run RED, then GREEN. Commit after every task.
- Gate rule: compare each `Run:` to `Expected:`; mismatch → STOP, fix, re-run; HALT after 3 tries. Final gate on the EXACT committed HEAD.

---

## CONFIRMED API SYMBOLS (verified against the repo — use exactly)

- **`GuildProvider`** (`domain/ports/GuildProvider.kt`): `guildOf(player: UUID): GuildRef?`, `guildById(id: String): GuildRef?`, `hasShopPermission(player: UUID, guildId: String, permission: GuildPermission): Boolean`, `enum GuildPermission { …, MANAGE_SHOPS }`. `data class GuildRef(val id: String, val name: String, …)`.
- **`SignDirection`** (`domain/shop/SignDirection.kt`): `enum { BUY, SELL, TRADE }`.
- **`Shop`** (`domain/shop/Shop.kt`): `guildId: UUID?`, `costAmount: Int`, `direction: SignDirection`, `stallId: String`.
- **`ContainerTradeService`** (`application/ContainerTradeService.kt`, `open class`, `@Suppress("TooManyFunctions")`): ctor `(stallRepository: StallRepository, economy: EconomyProvider, guildProvider: GuildProvider?, vaultService: ShopVaultService)`.
  - `executeBuy(shop, playerUuid)` — shop **buys** items (player is paid). Calls `canAffordShopCost(shop, ownerUuid)` then `executeBuyTransaction(shop, playerUuid, ctx, sellStack)`; inside, `val cost = shop.costAmount.toLong()`, then `withdrawFromShop(guildId, ownerUuid, cost)` (guild bank pays) + `economy.deposit(playerUuid, cost)`.
  - `executeSell(shop, playerUuid)` — shop **sells** items (player pays). `executeSellTransaction` does `val cost = shop.costAmount.toLong()`, `economy.withdraw(playerUuid, cost)`, `depositToShop(guildId, ownerUuid, cost)` (guild bank receives).
  - Helpers: `canAffordShopCost(shop, ownerUuid): Boolean` (reads `shop.costAmount`/`shop.guildId`); `withdrawFromShop(guildId: UUID?, ownerUuid, cost): Boolean`; `depositToShop(guildId: UUID?, ownerUuid, cost): Boolean` — the latter two already take an explicit `cost`.
  - Tests (`ContainerTradeServiceTest`, `ContainerTradeServiceTradeTest`) construct it as `object : ContainerTradeService(stallRepo, economy, guildProvider, vaultService) { override fun getContainer(...)=…; override fun deserializeStack(...)=… }`. **Adding a ctor param means updating these constructions.**
- **`@Repository`** = `net.badgersmc.nexus.annotations.Repository`. SQL repos are `@Repository class XxxSql(private val ds: DataSource) : Xxx`. Auto-discovered as beans.
- **Migrations:** `src/main/resources/migrations/`, latest `V016__shop_vault.sql`. Loaded by `MigrationRunner(ds, resourcePrefix="migrations", classLoader).runAll()` in `EnthusiaMarket.onEnable` — every `V*.sql` runs in filename order on boot. **Next file: `V017__guild_trade_policies.sql`.**
- **SQLite test pattern:** existing repo tests open an in-memory or temp-file SQLite `DataSource`, run the migration(s), then exercise the repo. Mirror `ShopRepositorySqlTest` / `SellOfferRepositorySqlTest` for the harness (HikariCP + `MigrationRunner`).
- **`GuildDissolutionService`** (`application/GuildDissolutionService.kt`, `@Service`): ctor `(stalls: StallRepository, eviction: StallEvictionService, shops: ShopRepository)`; `fun handle(guildId: String)`. Test constructs it directly with mocks.

---

## Task 1: Domain — PolicyKind + GuildTradePolicy

**Files:**
- Create: `src/main/kotlin/net/badgersmc/em/domain/guild/GuildTradePolicy.kt`
- Test: `src/test/kotlin/net/badgersmc/em/domain/guild/GuildTradePolicyTest.kt`

- [ ] **Step 1: Failing test**
```kotlin
package net.badgersmc.em.domain.guild

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GuildTradePolicyTest {
    @Test fun `tariff policy holds its rate`() {
        val p = GuildTradePolicy("g1", "g2", PolicyKind.TARIFF, 20)
        assertEquals(PolicyKind.TARIFF, p.kind)
        assertEquals(20, p.ratePct)
    }
    @Test fun `embargo policy ignores rate`() {
        val p = GuildTradePolicy("g1", "g2", PolicyKind.EMBARGO, 0)
        assertEquals(PolicyKind.EMBARGO, p.kind)
    }
    @Test fun `self-targeting is rejected`() {
        assertFailsWith<IllegalArgumentException> { GuildTradePolicy("g1", "g1", PolicyKind.TARIFF, 10) }
    }
    @Test fun `rate out of range is rejected`() {
        assertFailsWith<IllegalArgumentException> { GuildTradePolicy("g1", "g2", PolicyKind.TARIFF, -1) }
        assertFailsWith<IllegalArgumentException> { GuildTradePolicy("g1", "g2", PolicyKind.TARIFF, 1001) }
    }
}
```

- [ ] **Step 2: RED** — `./gradlew test --tests "*GuildTradePolicyTest" -Plumaguilds.jar=… --no-daemon --console=plain` → FAIL (class missing).

- [ ] **Step 3: Implement**
```kotlin
package net.badgersmc.em.domain.guild

/** How a guild treats another guild's members at its shops. */
enum class PolicyKind { TARIFF, EMBARGO }

/**
 * One guild's trade stance toward another guild. [ratePct] is the tariff
 * percentage (0..1000) and is ignored for [PolicyKind.EMBARGO].
 */
data class GuildTradePolicy(
    val ownerGuildId: String,
    val targetGuildId: String,
    val kind: PolicyKind,
    val ratePct: Int,
) {
    init {
        require(ownerGuildId != targetGuildId) { "A guild cannot set a trade policy on itself" }
        require(ratePct in 0..MAX_RATE_PCT) { "ratePct must be in 0..$MAX_RATE_PCT" }
    }
    companion object { const val MAX_RATE_PCT = 1000 }
}
```

- [ ] **Step 4: GREEN** — same command → PASS.
- [ ] **Step 5: Commit** — `feat(guild): GuildTradePolicy domain value + PolicyKind` + Co-Authored-By trailer.

---

## Task 2: Persistence — repository port, Sql impl, migration V017

**Files:**
- Create: `src/main/kotlin/net/badgersmc/em/domain/guild/GuildTradePolicyRepository.kt`
- Create: `src/main/kotlin/net/badgersmc/em/infrastructure/persistence/GuildTradePolicyRepositorySql.kt`
- Create: `src/main/resources/migrations/V017__guild_trade_policies.sql`
- Test: `src/test/kotlin/net/badgersmc/em/infrastructure/persistence/GuildTradePolicyRepositorySqlTest.kt`

- [ ] **Step 1: The migration**
```sql
-- V017__guild_trade_policies.sql — per-guild-pair tariff/embargo rules (guild trade policies)
CREATE TABLE IF NOT EXISTS guild_trade_policies (
    owner_guild_id  TEXT NOT NULL,
    target_guild_id TEXT NOT NULL,
    kind            TEXT NOT NULL,            -- TARIFF | EMBARGO
    rate_pct        INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (owner_guild_id, target_guild_id)
);
CREATE INDEX IF NOT EXISTS idx_gtp_owner ON guild_trade_policies(owner_guild_id);
```

- [ ] **Step 2: The port**
```kotlin
package net.badgersmc.em.domain.guild

interface GuildTradePolicyRepository {
    fun find(ownerGuildId: String, targetGuildId: String): GuildTradePolicy?
    fun listByOwner(ownerGuildId: String): List<GuildTradePolicy>
    fun upsert(policy: GuildTradePolicy)
    fun delete(ownerGuildId: String, targetGuildId: String)
    /** Delete every policy where [guildId] is the owner OR the target (disband cleanup). */
    fun deleteAllInvolving(guildId: String)
}
```

- [ ] **Step 3: Failing repository test** (mirror `SellOfferRepositorySqlTest`'s SQLite + MigrationRunner harness)
```kotlin
package net.badgersmc.em.infrastructure.persistence

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import net.badgersmc.em.domain.guild.GuildTradePolicy
import net.badgersmc.em.domain.guild.PolicyKind
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
```

- [ ] **Step 4: RED** — `./gradlew test --tests "*GuildTradePolicyRepositorySqlTest" …` → FAIL (impl missing).

- [ ] **Step 5: Implement the Sql repo**
```kotlin
package net.badgersmc.em.infrastructure.persistence

import net.badgersmc.em.domain.guild.GuildTradePolicy
import net.badgersmc.em.domain.guild.GuildTradePolicyRepository
import net.badgersmc.em.domain.guild.PolicyKind
import net.badgersmc.nexus.annotations.Repository
import javax.sql.DataSource

@Repository
class GuildTradePolicyRepositorySql(private val ds: DataSource) : GuildTradePolicyRepository {

    override fun find(ownerGuildId: String, targetGuildId: String): GuildTradePolicy? {
        ds.connection.use { conn ->
            conn.prepareStatement(
                "SELECT kind, rate_pct FROM guild_trade_policies WHERE owner_guild_id = ? AND target_guild_id = ?"
            ).use { ps ->
                ps.setString(1, ownerGuildId); ps.setString(2, targetGuildId)
                ps.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    return GuildTradePolicy(ownerGuildId, targetGuildId, PolicyKind.valueOf(rs.getString("kind")), rs.getInt("rate_pct"))
                }
            }
        }
    }

    override fun listByOwner(ownerGuildId: String): List<GuildTradePolicy> {
        val out = mutableListOf<GuildTradePolicy>()
        ds.connection.use { conn ->
            conn.prepareStatement(
                "SELECT target_guild_id, kind, rate_pct FROM guild_trade_policies WHERE owner_guild_id = ?"
            ).use { ps ->
                ps.setString(1, ownerGuildId)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        out.add(GuildTradePolicy(ownerGuildId, rs.getString("target_guild_id"),
                            PolicyKind.valueOf(rs.getString("kind")), rs.getInt("rate_pct")))
                    }
                }
            }
        }
        return out
    }

    override fun upsert(policy: GuildTradePolicy) {
        ds.connection.use { conn ->
            conn.prepareStatement(
                """
                INSERT INTO guild_trade_policies (owner_guild_id, target_guild_id, kind, rate_pct)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(owner_guild_id, target_guild_id)
                DO UPDATE SET kind = excluded.kind, rate_pct = excluded.rate_pct
                """.trimIndent()
            ).use { ps ->
                ps.setString(1, policy.ownerGuildId); ps.setString(2, policy.targetGuildId)
                ps.setString(3, policy.kind.name); ps.setInt(4, policy.ratePct)
                ps.executeUpdate()
            }
        }
    }

    override fun delete(ownerGuildId: String, targetGuildId: String) {
        ds.connection.use { conn ->
            conn.prepareStatement(
                "DELETE FROM guild_trade_policies WHERE owner_guild_id = ? AND target_guild_id = ?"
            ).use { ps -> ps.setString(1, ownerGuildId); ps.setString(2, targetGuildId); ps.executeUpdate() }
        }
    }

    override fun deleteAllInvolving(guildId: String) {
        ds.connection.use { conn ->
            conn.prepareStatement(
                "DELETE FROM guild_trade_policies WHERE owner_guild_id = ? OR target_guild_id = ?"
            ).use { ps -> ps.setString(1, guildId); ps.setString(2, guildId); ps.executeUpdate() }
        }
    }
}
```

- [ ] **Step 6: GREEN** — same command → PASS.
- [ ] **Step 7: Commit** — `feat(guild): GuildTradePolicy repository + V017 migration` + trailer.

---

## Task 3: GuildTradePolicyService.stanceFor

**Files:**
- Create: `src/main/kotlin/net/badgersmc/em/application/GuildTradePolicyService.kt`
- Test: `src/test/kotlin/net/badgersmc/em/application/GuildTradePolicyServiceTest.kt`

Direction semantics: a **SELL** shop charges the outsider (tariff raises price → `factor = 1 + r/100`); a **BUY** shop pays the outsider (tariff cuts payout → `factor = 1 − r/100`, floored at 0). `TRADE` never occurs on a guild shop → factor 1.0.

- [ ] **Step 1: Failing test**
```kotlin
package net.badgersmc.em.application

import io.mockk.every
import io.mockk.mockk
import net.badgersmc.em.domain.guild.GuildTradePolicy
import net.badgersmc.em.domain.guild.GuildTradePolicyRepository
import net.badgersmc.em.domain.guild.PolicyKind
import net.badgersmc.em.domain.ports.GuildProvider
import net.badgersmc.em.domain.shop.SignDirection
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class GuildTradePolicyServiceTest {
    private val buyer = UUID.randomUUID()
    private fun svc(repo: GuildTradePolicyRepository, gp: GuildProvider) = GuildTradePolicyService(repo, gp)

    // Mock GuildRef rather than construct it — GuildRef may carry extra fields (tag/emoji);
    // we only need `.id`.
    private fun gp(buyerGuild: String?): GuildProvider = mockk(relaxed = true) {
        every { guildOf(buyer) } returns buyerGuild?.let { gid ->
            mockk<GuildProvider.GuildRef> { every { id } returns gid }
        }
    }

    @Test fun `solo buyer is allowed at factor 1`() {
        val s = svc(mockk(relaxed = true), gp(null)).stanceFor("g1", buyer, SignDirection.SELL)
        assertEquals(1.0, assertIs<GuildTradePolicyService.TradeStance.Allowed>(s).factor)
    }
    @Test fun `own-guild buyer is allowed at factor 1`() {
        val s = svc(mockk(relaxed = true), gp("g1")).stanceFor("g1", buyer, SignDirection.SELL)
        assertEquals(1.0, assertIs<GuildTradePolicyService.TradeStance.Allowed>(s).factor)
    }
    @Test fun `no policy is allowed at factor 1`() {
        val repo = mockk<GuildTradePolicyRepository> { every { find("g1", "g2") } returns null }
        val s = svc(repo, gp("g2")).stanceFor("g1", buyer, SignDirection.SELL)
        assertEquals(1.0, assertIs<GuildTradePolicyService.TradeStance.Allowed>(s).factor)
    }
    @Test fun `embargo blocks`() {
        val repo = mockk<GuildTradePolicyRepository> { every { find("g1", "g2") } returns GuildTradePolicy("g1","g2",PolicyKind.EMBARGO,0) }
        assertIs<GuildTradePolicyService.TradeStance.Embargoed>(svc(repo, gp("g2")).stanceFor("g1", buyer, SignDirection.SELL))
    }
    @Test fun `tariff raises a SELL shop`() {
        val repo = mockk<GuildTradePolicyRepository> { every { find("g1", "g2") } returns GuildTradePolicy("g1","g2",PolicyKind.TARIFF,20) }
        assertEquals(1.2, assertIs<GuildTradePolicyService.TradeStance.Allowed>(svc(repo, gp("g2")).stanceFor("g1", buyer, SignDirection.SELL)).factor, 1e-9)
    }
    @Test fun `tariff cuts a BUY shop`() {
        val repo = mockk<GuildTradePolicyRepository> { every { find("g1", "g2") } returns GuildTradePolicy("g1","g2",PolicyKind.TARIFF,20) }
        assertEquals(0.8, assertIs<GuildTradePolicyService.TradeStance.Allowed>(svc(repo, gp("g2")).stanceFor("g1", buyer, SignDirection.BUY)).factor, 1e-9)
    }
    @Test fun `BUY tariff over 100 floors at 0`() {
        val repo = mockk<GuildTradePolicyRepository> { every { find("g1", "g2") } returns GuildTradePolicy("g1","g2",PolicyKind.TARIFF,150) }
        assertEquals(0.0, assertIs<GuildTradePolicyService.TradeStance.Allowed>(svc(repo, gp("g2")).stanceFor("g1", buyer, SignDirection.BUY)).factor, 1e-9)
    }
}
```

- [ ] **Step 2: RED** — `./gradlew test --tests "*GuildTradePolicyServiceTest" …` → FAIL.

- [ ] **Step 3: Implement (stanceFor only; management methods come in Task 4)**
```kotlin
package net.badgersmc.em.application

import net.badgersmc.em.domain.guild.GuildTradePolicyRepository
import net.badgersmc.em.domain.guild.PolicyKind
import net.badgersmc.em.domain.ports.GuildProvider
import net.badgersmc.em.domain.shop.SignDirection
import net.badgersmc.nexus.annotations.Service
import java.util.UUID

/**
 * Resolves a guild's trade stance toward a buyer, and (Task 4) lets MANAGE_SHOPS
 * members edit policies. Solo buyers and same-guild buyers are always exempt.
 */
@Service
class GuildTradePolicyService(
    private val policies: GuildTradePolicyRepository,
    private val guildProvider: GuildProvider,
) {
    sealed interface TradeStance {
        /** Trade proceeds; multiply the shop cost by [factor]. */
        data class Allowed(val factor: Double) : TradeStance
        /** The buyer's guild is embargoed; reject the trade. */
        data object Embargoed : TradeStance
    }

    fun stanceFor(ownerGuildId: String, buyer: UUID, direction: SignDirection): TradeStance {
        val buyerGuild = guildProvider.guildOf(buyer)?.id
        if (buyerGuild == null || buyerGuild == ownerGuildId) return TradeStance.Allowed(1.0)
        val policy = policies.find(ownerGuildId, buyerGuild) ?: return TradeStance.Allowed(1.0)
        return when (policy.kind) {
            PolicyKind.EMBARGO -> TradeStance.Embargoed
            PolicyKind.TARIFF -> TradeStance.Allowed(factorFor(direction, policy.ratePct))
        }
    }

    private fun factorFor(direction: SignDirection, ratePct: Int): Double = when (direction) {
        SignDirection.SELL -> 1.0 + ratePct / 100.0
        SignDirection.BUY -> (1.0 - ratePct / 100.0).coerceAtLeast(0.0)
        SignDirection.TRADE -> 1.0
    }
}
```

- [ ] **Step 4: GREEN** — same command → PASS.
- [ ] **Step 5: Commit** — `feat(guild): GuildTradePolicyService.stanceFor (tariff/embargo resolution)` + trailer.

---

## Task 4: Policy management methods (MANAGE_SHOPS-gated)

**Files:**
- Modify: `src/main/kotlin/net/badgersmc/em/application/GuildTradePolicyService.kt`
- Test: `src/test/kotlin/net/badgersmc/em/application/GuildTradePolicyServiceTest.kt`

- [ ] **Step 1: Failing tests** (append)
```kotlin
    @Test fun `setTariff persists when actor has MANAGE_SHOPS`() {
        val repo = mockk<GuildTradePolicyRepository>(relaxed = true)
        val gpm = mockk<GuildProvider>(relaxed = true)
        every { gpm.hasShopPermission(buyer, "g1", GuildProvider.GuildPermission.MANAGE_SHOPS) } returns true
        val r = GuildTradePolicyService(repo, gpm).setTariff(buyer, "g1", "g2", 30)
        assertIs<GuildTradePolicyService.PolicyResult.Ok>(r)
        io.mockk.verify { repo.upsert(match { it.ownerGuildId=="g1" && it.targetGuildId=="g2" && it.kind==PolicyKind.TARIFF && it.ratePct==30 }) }
    }
    @Test fun `setTariff denied without MANAGE_SHOPS`() {
        val repo = mockk<GuildTradePolicyRepository>(relaxed = true)
        val gpm = mockk<GuildProvider>(relaxed = true)
        every { gpm.hasShopPermission(buyer, "g1", any()) } returns false
        assertIs<GuildTradePolicyService.PolicyResult.Denied>(GuildTradePolicyService(repo, gpm).setTariff(buyer, "g1", "g2", 30))
        io.mockk.verify(exactly = 0) { repo.upsert(any()) }
    }
    @Test fun `self-target is rejected`() {
        val gpm = mockk<GuildProvider>(relaxed = true); every { gpm.hasShopPermission(any(), any(), any()) } returns true
        assertIs<GuildTradePolicyService.PolicyResult.Invalid>(GuildTradePolicyService(mockk(relaxed=true), gpm).setTariff(buyer, "g1", "g1", 10))
    }
    @Test fun `rate out of range is rejected`() {
        val gpm = mockk<GuildProvider>(relaxed = true); every { gpm.hasShopPermission(any(), any(), any()) } returns true
        assertIs<GuildTradePolicyService.PolicyResult.Invalid>(GuildTradePolicyService(mockk(relaxed=true), gpm).setTariff(buyer, "g1", "g2", 2000))
    }
```

- [ ] **Step 2: RED** — same `*GuildTradePolicyServiceTest` command → new tests FAIL.

- [ ] **Step 3: Implement (add to the class)**
```kotlin
    sealed interface PolicyResult {
        data object Ok : PolicyResult
        data object Denied : PolicyResult            // actor lacks MANAGE_SHOPS
        data class Invalid(val reason: String) : PolicyResult
    }

    fun setTariff(actor: UUID, ownerGuildId: String, targetGuildId: String, ratePct: Int): PolicyResult =
        mutate(actor, ownerGuildId, targetGuildId) {
            if (ratePct !in 0..net.badgersmc.em.domain.guild.GuildTradePolicy.MAX_RATE_PCT)
                return@mutate PolicyResult.Invalid("rate must be 0..${net.badgersmc.em.domain.guild.GuildTradePolicy.MAX_RATE_PCT}")
            policies.upsert(net.badgersmc.em.domain.guild.GuildTradePolicy(ownerGuildId, targetGuildId, PolicyKind.TARIFF, ratePct))
            PolicyResult.Ok
        }

    fun setEmbargo(actor: UUID, ownerGuildId: String, targetGuildId: String): PolicyResult =
        mutate(actor, ownerGuildId, targetGuildId) {
            policies.upsert(net.badgersmc.em.domain.guild.GuildTradePolicy(ownerGuildId, targetGuildId, PolicyKind.EMBARGO, 0))
            PolicyResult.Ok
        }

    fun clear(actor: UUID, ownerGuildId: String, targetGuildId: String): PolicyResult =
        mutate(actor, ownerGuildId, targetGuildId) {
            policies.delete(ownerGuildId, targetGuildId); PolicyResult.Ok
        }

    fun list(ownerGuildId: String): List<net.badgersmc.em.domain.guild.GuildTradePolicy> = policies.listByOwner(ownerGuildId)

    private inline fun mutate(
        actor: UUID, ownerGuildId: String, targetGuildId: String, action: () -> PolicyResult
    ): PolicyResult {
        if (ownerGuildId == targetGuildId) return PolicyResult.Invalid("A guild cannot set a policy on itself")
        if (!guildProvider.hasShopPermission(actor, ownerGuildId, GuildProvider.GuildPermission.MANAGE_SHOPS))
            return PolicyResult.Denied
        return action()
    }
```

- [ ] **Step 4: GREEN** — same command → PASS (incl. the Task-3 stanceFor tests).
- [ ] **Step 5: Commit** — `feat(guild): policy management methods (MANAGE_SHOPS-gated)` + trailer.

---

## Task 5: ContainerTradeService — embargo reject + tariff factor

**Files:**
- Modify: `src/main/kotlin/net/badgersmc/em/application/ContainerTradeService.kt`
- Test: `src/test/kotlin/net/badgersmc/em/application/ContainerTradeServiceTradeTest.kt` (add cases) + update existing constructions

- [ ] **Step 1: Failing tests** — add guild-policy cases to `ContainerTradeServiceTradeTest` (read it first for the override/mocks pattern). Construct the service with a `policyService` whose `stanceFor` is stubbed:
```kotlin
    // helper: build a guild SELL shop (shop.guildId != null, direction = SELL), price 100
    @Test fun `guild SELL shop applies a tariff factor to the price the buyer pays`() {
        // policyService.stanceFor(guildId, player, SELL) returns Allowed(1.2)
        // execute the sell (player buys) → verify the guild bank receives 120 (100 * 1.2) via depositToShop / bankDeposit,
        // and the player is charged 120.
    }
    @Test fun `guild shop embargo rejects the trade`() {
        // policyService.stanceFor(...) returns Embargoed → result is Failure, no economy/vault calls.
    }
    @Test fun `solo shop ignores policy (factor unaffected)`() {
        // shop.guildId == null → policyService.stanceFor is never called; cost == base. (regression guard)
    }
```
Stub: `every { policyService.stanceFor(any(), any(), any()) } returns GuildTradePolicyService.TradeStance.Allowed(1.2)` (and an Embargoed variant). Use the guild-bank mocks already in the suite (`guildProvider.bankDeposit/bankWithdraw`).

- [ ] **Step 2: RED** — `./gradlew test --tests "*ContainerTradeServiceTradeTest" …` → new cases FAIL (factor not applied / embargo not rejected); existing solo cases still pass once the new ctor arg is supplied.

- [ ] **Step 3: Implement**

(a) Add the dependency to the ctor (after `vaultService`):
```kotlin
    private val vaultService: ShopVaultService,
    private val policyService: GuildTradePolicyService? = null,
```
(`?  = null` keeps DI flexible and means "no policy engine" → factor 1.0; Nexus injects the bean in production.)

(b) Add the resolver + a sealed result near the bottom of the class:
```kotlin
    private sealed interface EffectiveCost {
        data class Cost(val value: Long) : EffectiveCost
        data object Embargoed : EffectiveCost
    }

    private fun effectiveCostOrEmbargo(shop: Shop, playerUuid: UUID): EffectiveCost {
        val base = shop.costAmount.toLong()
        val guildId = shop.guildId ?: return EffectiveCost.Cost(base)
        val svc = policyService ?: return EffectiveCost.Cost(base)
        return when (val stance = svc.stanceFor(guildId.toString(), playerUuid, shop.direction)) {
            is GuildTradePolicyService.TradeStance.Embargoed -> EffectiveCost.Embargoed
            is GuildTradePolicyService.TradeStance.Allowed ->
                EffectiveCost.Cost((base * stance.factor).roundToLong().coerceAtLeast(0L))
        }
    }
```
(Add `import kotlin.math.roundToLong` — it's an extension on `Double`.)

(c) `executeBuy` — after the `sellAmount/costAmount` check, resolve and thread the cost:
```kotlin
    fun executeBuy(shop: Shop, playerUuid: UUID): ContainerTradeResult {
        if (shop.frozen) return ContainerTradeResult.Failure("This shop is frozen")
        if (shop.sellAmount <= 0 || shop.costAmount <= 0) return ContainerTradeResult.Failure("Invalid trade amounts")
        val eff = effectiveCostOrEmbargo(shop, playerUuid)
        if (eff is EffectiveCost.Embargoed) return ContainerTradeResult.Failure("Your guild is embargoed by this shop's guild")
        val cost = (eff as EffectiveCost.Cost).value
        val preconditions = buyPreconditions(shop, playerUuid)
        if (preconditions.result != null) return preconditions.result!!
        if (!canAffordShopCost(cost, shop.guildId, preconditions.ownerUuid!!)) return ContainerTradeResult.Failure("Shop can't afford this")
        return executeBuyTransaction(shop, playerUuid, preconditions.ctx!!, preconditions.sellStack!!, cost)
    }
```

(d) `canAffordShopCost` — take the effective cost + guildId:
```kotlin
    private fun canAffordShopCost(cost: Long, guildId: UUID?, ownerUuid: UUID): Boolean {
        return if (guildId != null) {
            guildProvider != null && guildProvider.bankBalance(guildId.toString()) >= cost
        } else {
            economy.balance(ownerUuid) >= cost
        }
    }
```

(e) `executeBuyTransaction` — accept `cost` instead of recomputing; delete the internal `val cost = shop.costAmount.toLong()`:
```kotlin
    private fun executeBuyTransaction(
        shop: Shop, playerUuid: UUID, ctx: TradeContext, sellStack: ItemStack, cost: Long
    ): ContainerTradeResult {
        // ... unchanged removal/add logic ...
        val guildId = shop.guildId
        val withdrawSuccess = withdrawFromShop(guildId, ctx.ownerUuid, cost)
        // ... rest unchanged ...
    }
```

(f) `executeSell` — resolve, reject embargo, thread cost:
```kotlin
    fun executeSell(shop: Shop, playerUuid: UUID): ContainerTradeResult {
        if (shop.frozen) return ContainerTradeResult.Failure("This shop is frozen")
        if (shop.sellAmount <= 0 || shop.costAmount <= 0) return ContainerTradeResult.Failure("Invalid trade amounts")
        val eff = effectiveCostOrEmbargo(shop, playerUuid)
        if (eff is EffectiveCost.Embargoed) return ContainerTradeResult.Failure("Your guild is embargoed by this shop's guild")
        val cost = (eff as EffectiveCost.Cost).value
        val preconditions = sellPreconditions(shop, playerUuid)
        if (preconditions.result != null) return preconditions.result!!
        return executeSellTransaction(shop, playerUuid, preconditions.ctx!!, preconditions.sellStack!!, cost)
    }
```

(g) `executeSellTransaction` — accept `cost`, delete the internal recompute:
```kotlin
    private fun executeSellTransaction(
        shop: Shop, playerUuid: UUID, ctx: TradeContext, sellStack: ItemStack, cost: Long
    ): ContainerTradeResult {
        if (economy.balance(playerUuid) < cost) return ContainerTradeResult.Failure("Insufficient funds")
        // ... rest unchanged (already uses `cost`) ...
    }
```

(h) Update the existing test constructions (`ContainerTradeServiceTest`, `ContainerTradeServiceTradeTest`) to pass the new arg: `object : ContainerTradeService(stallRepo, economy, guildProvider, vaultService, policyService) { … }` — use `mockk<GuildTradePolicyService>(relaxed = true)` (its relaxed `stanceFor` returns a mock; for the *solo* regression tests `shop.guildId` is null so it's never called; for tests that DO need a real factor, stub `stanceFor`). If a relaxed mock's `stanceFor` is awkward, pass `null` for the solo/non-guild tests (null → factor 1.0).

- [ ] **Step 4: GREEN** — `./gradlew test --tests "*ContainerTradeService*" …` → PASS (new guild cases + all existing).
- [ ] **Step 5: Commit** — `feat(trade): apply guild tariff/embargo to container-shop trades` + trailer.

---

## Task 6: Disband cleanup — delete a dissolved guild's policies

**Files:**
- Modify: `src/main/kotlin/net/badgersmc/em/application/GuildDissolutionService.kt`
- Test: `src/test/kotlin/net/badgersmc/em/application/GuildDissolutionServiceTest.kt`

- [ ] **Step 1: Failing test** (append; add a `GuildTradePolicyRepository` mock to the service construction in the test's builder)
```kotlin
    @Test fun `handle deletes the disbanded guild's trade policies`() {
        // build service with a mockk<GuildTradePolicyRepository>(relaxed = true) as the new dep
        // call service.handle("gD")
        // verify { policyRepo.deleteAllInvolving("gD") }
    }
```

- [ ] **Step 2: RED** — `./gradlew test --tests "*GuildDissolutionServiceTest" …` → FAIL (ctor arg / call missing).

- [ ] **Step 3: Implement** — add the dep + the call:
```kotlin
class GuildDissolutionService(
    private val stalls: StallRepository,
    private val eviction: StallEvictionService,
    private val shops: ShopRepository,
    private val policies: net.badgersmc.em.domain.guild.GuildTradePolicyRepository,
) {
    // ... in handle(guildId), after the stall + shop cleanup, before the summary log:
    try {
        policies.deleteAllInvolving(guildId)
    } catch (e: Exception) {
        log.warning("GuildDissolution: failed to delete trade policies for guild=$guildId: ${e.message}")
    }
```
Update every `GuildDissolutionService(...)` construction (production wiring is DI — automatic; only the test constructs it directly) with the new mock arg.

- [ ] **Step 4: GREEN** — same command → PASS.
- [ ] **Step 5: Commit** — `feat(guild): disband cleanup also drops trade policies (M-16 tie-in)` + trailer.

---

## Task 7: Final gate

- [ ] **Step 1: Full verification on committed HEAD**

Run: `./gradlew clean detekt test shadowJar -Plumaguilds.jar=… --no-daemon --console=plain`
Expected: BUILD SUCCESSFUL, detekt 0, all tests pass, shadowJar built. If `ContainerTradeService` trips detekt complexity from the new resolver, the `effectiveCostOrEmbargo` helper already isolates it — keep `executeBuy`/`executeSell` flat; do not inline.

- [ ] **Step 2: Report** — gate output + the 6 commit hashes. Do NOT push to BadgersMC; push the branch to `fork` only.

---

## Self-Review Notes (for the implementer)
1. **The guild bank captures the tariff automatically** — you only change `cost`; do not add separate surcharge transfers. SELL → `cost×(1+r/100)` all flows to the guild bank; BUY → `cost×(1−r/100)` is all the guild pays out.
2. **Solo / own-guild / no-policy = factor 1.0** → byte-identical to today. The solo regression test is the guard; `shop.guildId == null` short-circuits before `stanceFor`.
3. **Direction is the shop's `direction`** — SELL raises, BUY cuts. Both `executeBuy`/`executeSell` feed `shop.direction` to one resolver, so it's self-consistent.
4. **Thread the effective cost** — `canAffordShopCost`, `executeBuyTransaction`, `executeSellTransaction` must use the passed `cost`, not re-read `shop.costAmount`, or the tariff is lost on the afford/payout side.
5. **No UI here** — `/em guild policy`, menus, and `listGuilds` are PR-B. Scope is the engine + disband tie-in only.
