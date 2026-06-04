# Misc / Integration Implementation Plan (ItemShops Parity SP6)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Four ItemShops integration features on EM's money model — a shop **transaction log** (history), **owner sale notifications** (online + offline), **PAPI placeholders**, and a **sign-click info card**. The transaction table is the spine: history, both notification paths, and one placeholder read it.

**Architecture:** Hexagonal/SPEAR. New persistence aggregate (`ShopTransaction` + repository, migration V015) fed by an event listener; three thin read paths (history command, PAPI expansion, info card).

**Tech Stack:** Kotlin 2.0.0, Nexus DI + commands + listeners + persistence + papi, IFramework, JUnit 5 + MockK + MockBukkit, detekt 1.23.8.

**Reference spec:** `docs/superpowers/specs/2026-06-04-itemshops-parity-misc-integration-design.md`

**Standing rules (every task):**
- Prefix bash with `cd <REPO> &&` (`/d/BadgersMC-Dev/EnthusiaMarket`, or `/opt/data/EnthusiaMarket`). On Hermes' box also prefix gradle with `export JAVA_HOME=/opt/data/jdk-21.0.11+10 &&`.
- Every gradle command includes `-Plumaguilds.jar=<JAR> --no-daemon --console=plain` (`/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar` or `/opt/data/...`).
- LF→CRLF git warnings expected. Branch `feat/misc-integration`. Do not push (coordinator opens the PR).
- TDD: write failing test, run RED, then GREEN. Commit after every task with the given message.
- Gate rule: compare each `Run:` to `Expected:`; mismatch → STOP, fix, re-run; HALT after 3 tries. Final gate runs on the EXACT committed HEAD.

---

## CONFIRMED API SYMBOLS (verified against the repo — use exactly)

- **Migrations:** dir `src/main/resources/migrations/`; next number **V015**. SQLite-primary; ids are `id INTEGER PRIMARY KEY AUTOINCREMENT` (see `shop_items`). `MigrationRunner(ds, resourcePrefix = "migrations", classLoader).runAll()` already runs in `onEnable`, so V015 auto-applies.
- **`@Repository`** (`net.badgersmc.nexus.annotations.Repository`): a repository is `@Repository class XSql(private val ds: DataSource) : XRepository`. Nexus auto-discovers it and injects the `DataSource` bean (registered in `onEnable`). Mirror `ShopRepositorySql` for `Connection`/`PreparedStatement` usage (`ds.connection.use { ... }`, `ps.setString/​setLong/​setInt/​setBoolean`, `getGeneratedKeys`).
- **`ShopRepository`**: `findByOwner(owner: UUID): List<Shop>`, `all(): List<Shop>`.
- **`Shop`** fields used: `id: Long`, `owner: UUID`, `direction: SignDirection`, `sellItem: String`, `sellAmount: Int`, `costAmount: Int`, `signWorld/signX/signY/signZ`, `containerWorld/containerX/containerY/containerZ`.
- **`SignDirection`**: `enum { BUY, SELL }` (`net.badgersmc.em.domain.shop`).
- **`ItemStackSerializer`** (`application/`): `deserialize(base64): ItemStack?`.
- **`PostShopTransactionEvent`** (`net.badgersmc.em.events`): current ctor `(buyer: Player, landlordId: UUID, item: ItemStack, quantity: Int, pricePaid: Double)`; `companion val handlerList`. **Add** `shopId: Long = 0` and `direction: SignDirection = SignDirection.SELL` as trailing defaulted params (back-compat).
- **`ContainerTradeService`** (`application/`): private `fireTransactionEvent(player, ownerUuid, item, quantity, cost)` at ~L197; called from `executeBuyTransaction` (~L99) and `executeSellTransaction` (~L155), both of which have the `shop` param in scope. **Add** `shopId` + `direction` params to `fireTransactionEvent` and pass `shop.id`, `shop.direction` from both call sites. (Also record the transaction here — see Task 2.)
- **Nexus listeners:** `@net.badgersmc.nexus.paper.listeners.Listener` + `@Component` on an `open class X(...) : org.bukkit.event.Listener` with `@EventHandler fun ...`; auto-registered by `NexusListenerRegistry` (no manual `registerEvents`). See `SignPlaceListener`.
- **`LangService`**: `lang.msg("key", "tok" to v)`; placeholders `<token>` (NEVER `{token}`).
- **`ShopCommands`** (`infrastructure/commands/ShopCommands.kt`): `@Command(name="shop", aliases=["shops"])`; inject `ShopTransactionRepository` for `/shop history`. Subcommand pattern: `@Subcommand("history") @Permission("enthusiamarket.shop.use") fun history(@Context sender, @...Arg("page") page: Int = 1)`.
- **nexus-papi:** NOT yet a dependency (Task 5 adds it). API: annotate `@net.badgersmc.nexus.papi.PapiExpansion(identifier = "enthusiamarket")` + `@Component` on a class implementing `net.badgersmc.nexus.papi.PlaceholderResolver { fun resolve(player: org.bukkit.OfflinePlayer?, params: String): String? }`. Register in `onEnable` via `net.badgersmc.nexus.papi.registerNexusExpansions("net.badgersmc.em", this::class.java.classLoader, ctx)` (no-ops if PAPI absent). `params` is everything after the identifier, e.g. `"shops_owned"`.
- **`ShopInteractListener`** (`infrastructure/listeners/`): `onSignRightClick(event: PlayerInteractEvent)` resolves the shop, sets `event.isCancelled = true`, then opens the menu. Inject nothing new; add a `if (event.player.isSneaking) { <send info card>; return }` branch after the shop is resolved and the event cancelled, before the menu opens. `event.player.isSneaking: Boolean`.
- **`EnthusiaMarketConfig.Shop`** (`config/`): a `class Shop { @Comment(...) var x = ... }` accessed as `config.shop.<field>`. Add `notifyEnabled` and `historyRetentionDays`.
- **`onEnable`** (`EnthusiaMarket.kt`): `ctx` (NexusContext) created ~L48; `MigrationRunner...runAll()` ~L90; listeners registered in Phase 6. Add the PAPI registration + the retention prune after the DI context + dataSource exist.

---

## Task 1: ShopTransaction + V015 + repository

**Files:**
- Create: `src/main/kotlin/net/badgersmc/em/domain/shop/ShopTransaction.kt`
- Create: `src/main/kotlin/net/badgersmc/em/domain/shop/ShopTransactionRepository.kt`
- Create: `src/main/resources/migrations/V015__shop_transactions.sql`
- Create: `src/main/kotlin/net/badgersmc/em/infrastructure/persistence/ShopTransactionRepositorySql.kt`
- Test: `src/test/kotlin/net/badgersmc/em/infrastructure/persistence/ShopTransactionRepositorySqlTest.kt`

- [ ] **Step 1: Domain types**

`ShopTransaction.kt`:
```kotlin
package net.badgersmc.em.domain.shop

import java.util.UUID

/** A completed shop trade, persisted for history + owner notifications (ItemShops parity SP6). */
data class ShopTransaction(
    val id: Long = 0,
    val shopId: Long,
    val owner: UUID,
    val buyer: UUID,
    val direction: SignDirection,
    val item: String,        // material name (lowercase)
    val quantity: Int,
    val totalPrice: Long,
    val createdAt: Long,     // epoch millis
    val notified: Boolean = false,
)
```

`ShopTransactionRepository.kt`:
```kotlin
package net.badgersmc.em.domain.shop

import java.util.UUID

interface ShopTransactionRepository {
    fun record(tx: ShopTransaction): ShopTransaction
    /** Newest-first, paged. */
    fun findByOwner(owner: UUID, limit: Int, offset: Int): List<ShopTransaction>
    fun countUnnotified(owner: UUID): Int
    fun markNotified(owner: UUID)
    /** Delete rows older than [beforeMs]; returns rows removed. */
    fun prune(beforeMs: Long): Int
}
```

- [ ] **Step 2: Migration V015**

`src/main/resources/migrations/V015__shop_transactions.sql`:
```sql
-- ItemShops parity SP6 — shop trade log. Powers /shop history, owner sale
-- notifications (notified flag = the unseen queue), and the sales_unseen placeholder.
CREATE TABLE IF NOT EXISTS shop_transactions (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    shop_id      INTEGER NOT NULL,
    owner        TEXT NOT NULL,
    buyer        TEXT NOT NULL,
    direction    TEXT NOT NULL,
    item         TEXT NOT NULL,
    quantity     INTEGER NOT NULL,
    total_price  INTEGER NOT NULL,
    created_at   INTEGER NOT NULL,
    notified     INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_shop_tx_owner ON shop_transactions(owner);
CREATE INDEX IF NOT EXISTS idx_shop_tx_created ON shop_transactions(created_at);
```

- [ ] **Step 3: Failing persistence test**

`ShopTransactionRepositorySqlTest.kt`:
```kotlin
package net.badgersmc.em.infrastructure.persistence

import net.badgersmc.em.domain.shop.ShopTransaction
import net.badgersmc.em.domain.shop.SignDirection
import net.badgersmc.nexus.persistence.DatabaseFactory
import net.badgersmc.nexus.persistence.DatabaseSpec
import net.badgersmc.nexus.persistence.MigrationRunner
import org.mockbukkit.mockbukkit.MockBukkit
import java.io.File
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ShopTransactionRepositorySqlTest {

    private val dbFile = File.createTempFile("em-shop-tx", ".db")
    private lateinit var ds: DataSource

    @BeforeTest fun setup() {
        MockBukkit.mock()
        ds = DatabaseFactory.open(DatabaseSpec.Sqlite(dbFile)).also {
            MigrationRunner(it, resourcePrefix = "migrations", classLoader = javaClass.classLoader).runAll()
        }
    }

    @AfterTest fun teardown() { MockBukkit.unmock(); dbFile.delete() }

    private fun tx(owner: UUID, createdAt: Long, notified: Boolean = false) = ShopTransaction(
        shopId = 1, owner = owner, buyer = UUID.randomUUID(), direction = SignDirection.SELL,
        item = "diamond", quantity = 5, totalPrice = 100, createdAt = createdAt, notified = notified,
    )

    @Test fun `record then find by owner newest-first`() {
        val repo = ShopTransactionRepositorySql(ds)
        val owner = UUID.randomUUID()
        repo.record(tx(owner, createdAt = 1_000))
        repo.record(tx(owner, createdAt = 2_000))
        val rows = repo.findByOwner(owner, limit = 10, offset = 0)
        assertEquals(2, rows.size)
        assertEquals(2_000, rows.first().createdAt) // newest first
    }

    @Test fun `countUnnotified and markNotified`() {
        val repo = ShopTransactionRepositorySql(ds)
        val owner = UUID.randomUUID()
        repo.record(tx(owner, createdAt = 1_000))
        repo.record(tx(owner, createdAt = 2_000))
        assertEquals(2, repo.countUnnotified(owner))
        repo.markNotified(owner)
        assertEquals(0, repo.countUnnotified(owner))
    }

    @Test fun `prune deletes only older rows`() {
        val repo = ShopTransactionRepositorySql(ds)
        val owner = UUID.randomUUID()
        repo.record(tx(owner, createdAt = 1_000))
        repo.record(tx(owner, createdAt = 5_000))
        assertEquals(1, repo.prune(beforeMs = 2_000))
        assertEquals(1, repo.findByOwner(owner, 10, 0).size)
    }
}
```

- [ ] **Step 4: Run the test — verify it fails**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew test --tests "net.badgersmc.em.infrastructure.persistence.ShopTransactionRepositorySqlTest" -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: FAIL — `ShopTransactionRepositorySql` not defined.

- [ ] **Step 5: Implement the repository**

`ShopTransactionRepositorySql.kt`:
```kotlin
package net.badgersmc.em.infrastructure.persistence

import net.badgersmc.em.domain.shop.ShopTransaction
import net.badgersmc.em.domain.shop.ShopTransactionRepository
import net.badgersmc.em.domain.shop.SignDirection
import net.badgersmc.nexus.annotations.Repository
import java.sql.ResultSet
import java.util.UUID
import javax.sql.DataSource

@Repository
class ShopTransactionRepositorySql(private val ds: DataSource) : ShopTransactionRepository {

    override fun record(tx: ShopTransaction): ShopTransaction {
        ds.connection.use { c ->
            c.prepareStatement(
                """INSERT INTO shop_transactions
                   (shop_id, owner, buyer, direction, item, quantity, total_price, created_at, notified)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                java.sql.Statement.RETURN_GENERATED_KEYS,
            ).use { ps ->
                ps.setLong(1, tx.shopId)
                ps.setString(2, tx.owner.toString())
                ps.setString(3, tx.buyer.toString())
                ps.setString(4, tx.direction.name)
                ps.setString(5, tx.item)
                ps.setInt(6, tx.quantity)
                ps.setLong(7, tx.totalPrice)
                ps.setLong(8, tx.createdAt)
                ps.setBoolean(9, tx.notified)
                ps.executeUpdate()
                val id = ps.generatedKeys.use { if (it.next()) it.getLong(1) else 0L }
                return tx.copy(id = id)
            }
        }
    }

    override fun findByOwner(owner: UUID, limit: Int, offset: Int): List<ShopTransaction> {
        ds.connection.use { c ->
            c.prepareStatement(
                """SELECT * FROM shop_transactions WHERE owner = ?
                   ORDER BY created_at DESC LIMIT ? OFFSET ?"""
            ).use { ps ->
                ps.setString(1, owner.toString())
                ps.setInt(2, limit)
                ps.setInt(3, offset)
                ps.executeQuery().use { rs ->
                    val out = mutableListOf<ShopTransaction>()
                    while (rs.next()) out += map(rs)
                    return out
                }
            }
        }
    }

    override fun countUnnotified(owner: UUID): Int {
        ds.connection.use { c ->
            c.prepareStatement("SELECT COUNT(*) FROM shop_transactions WHERE owner = ? AND notified = 0").use { ps ->
                ps.setString(1, owner.toString())
                ps.executeQuery().use { rs -> return if (rs.next()) rs.getInt(1) else 0 }
            }
        }
    }

    override fun markNotified(owner: UUID) {
        ds.connection.use { c ->
            c.prepareStatement("UPDATE shop_transactions SET notified = 1 WHERE owner = ? AND notified = 0").use { ps ->
                ps.setString(1, owner.toString())
                ps.executeUpdate()
            }
        }
    }

    override fun prune(beforeMs: Long): Int {
        ds.connection.use { c ->
            c.prepareStatement("DELETE FROM shop_transactions WHERE created_at < ?").use { ps ->
                ps.setLong(1, beforeMs)
                return ps.executeUpdate()
            }
        }
    }

    private fun map(rs: ResultSet) = ShopTransaction(
        id = rs.getLong("id"),
        shopId = rs.getLong("shop_id"),
        owner = UUID.fromString(rs.getString("owner")),
        buyer = UUID.fromString(rs.getString("buyer")),
        direction = SignDirection.valueOf(rs.getString("direction")),
        item = rs.getString("item"),
        quantity = rs.getInt("quantity"),
        totalPrice = rs.getLong("total_price"),
        createdAt = rs.getLong("created_at"),
        notified = rs.getBoolean("notified"),
    )
}
```

- [ ] **Step 6: Run the test — verify it passes**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew test --tests "net.badgersmc.em.infrastructure.persistence.ShopTransactionRepositorySqlTest" -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
cd /d/BadgersMC-Dev/EnthusiaMarket && git add src/main/kotlin/net/badgersmc/em/domain/shop/ShopTransaction.kt src/main/kotlin/net/badgersmc/em/domain/shop/ShopTransactionRepository.kt src/main/resources/migrations/V015__shop_transactions.sql src/main/kotlin/net/badgersmc/em/infrastructure/persistence/ShopTransactionRepositorySql.kt src/test/kotlin/net/badgersmc/em/infrastructure/persistence/ShopTransactionRepositorySqlTest.kt
git commit -m "feat(shop): ShopTransaction log + V015 + repository (SP6)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 2: Enrich PostShopTransactionEvent + record on trade

**Files:**
- Modify: `src/main/kotlin/net/badgersmc/em/events/PostShopTransactionEvent.kt`
- Modify: `src/main/kotlin/net/badgersmc/em/application/ContainerTradeService.kt`
- Create: `src/main/kotlin/net/badgersmc/em/infrastructure/listeners/ShopTransactionRecorder.kt`

- [ ] **Step 1: Add shopId + direction to the event**

In `PostShopTransactionEvent.kt`, add two trailing constructor params with defaults (back-compat):
```kotlin
    val pricePaid: Double,
    val shopId: Long = 0,
    val direction: net.badgersmc.em.domain.shop.SignDirection = net.badgersmc.em.domain.shop.SignDirection.SELL,
```

- [ ] **Step 2: Pass shop context from ContainerTradeService**

Read `ContainerTradeService.kt`. Change `fireTransactionEvent(...)` to accept `shopId: Long` and `direction: net.badgersmc.em.domain.shop.SignDirection`, set them on the event, and update both call sites (in `executeBuyTransaction` and `executeSellTransaction`) to pass `shop.id` and `shop.direction`:
```kotlin
        fireTransactionEvent(ctx.player, ctx.ownerUuid, sellStack, shop.sellAmount, cost, shop.id, shop.direction)
```
```kotlin
    private fun fireTransactionEvent(
        player: Player, ownerUuid: UUID, item: ItemStack, quantity: Int, cost: Long,
        shopId: Long, direction: net.badgersmc.em.domain.shop.SignDirection,
    ) {
        Bukkit.getPluginManager().callEvent(
            net.badgersmc.em.events.PostShopTransactionEvent(
                buyer = player, landlordId = ownerUuid,
                item = item, quantity = quantity, pricePaid = cost.toDouble(),
                shopId = shopId, direction = direction,
            )
        )
    }
```

- [ ] **Step 3: Recorder listener**

`ShopTransactionRecorder.kt`:
```kotlin
package net.badgersmc.em.infrastructure.listeners

import net.badgersmc.em.domain.shop.ShopTransaction
import net.badgersmc.em.domain.shop.ShopTransactionRepository
import net.badgersmc.em.events.PostShopTransactionEvent
import net.badgersmc.nexus.annotations.Component
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener

/** Persists every completed shop trade to the transaction log (ItemShops parity SP6). */
@net.badgersmc.nexus.paper.listeners.Listener
@Component
open class ShopTransactionRecorder(
    private val transactions: ShopTransactionRepository,
) : Listener {

    @EventHandler
    @Suppress("TooGenericExceptionCaught")
    fun onTransaction(event: PostShopTransactionEvent) {
        try {
            transactions.record(
                ShopTransaction(
                    shopId = event.shopId,
                    owner = event.landlordId,
                    buyer = event.buyer.uniqueId,
                    direction = event.direction,
                    item = event.item.type.name.lowercase(),
                    quantity = event.quantity,
                    totalPrice = event.pricePaid.toLong(),
                    createdAt = System.currentTimeMillis(),
                    notified = false,
                )
            )
        } catch (e: Exception) {
            // History is best-effort: a log write must never disturb the completed trade.
            org.bukkit.Bukkit.getLogger().warning("Failed to record shop transaction: ${e.message}")
        }
    }
}
```

- [ ] **Step 4: Build**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew compileKotlin compileTestKotlin -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: BUILD SUCCESSFUL. (If a `ContainerTradeService` test constructs the event or calls the private fire helper, adapt to the new params.)

- [ ] **Step 5: Commit**

```bash
cd /d/BadgersMC-Dev/EnthusiaMarket && git add src/main/kotlin/net/badgersmc/em/events/PostShopTransactionEvent.kt src/main/kotlin/net/badgersmc/em/application/ContainerTradeService.kt src/main/kotlin/net/badgersmc/em/infrastructure/listeners/ShopTransactionRecorder.kt
git commit -m "feat(shop): record completed trades to the transaction log (SP6)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 3: `/shop history` command

**Files:**
- Modify: `src/main/kotlin/net/badgersmc/em/infrastructure/commands/ShopCommands.kt`
- Modify: `src/main/resources/lang/en_US.yml`

- [ ] **Step 1: Lang keys**

In `en_US.yml`, under `shop:` add a `history` block (two-space indent, `<token>`):
```yaml
  history:
    header: "<gold>Your shop sales <gray>(page <page>)"
    line: "<gray>- <white><when></white> <dark_gray>| <green><qty>x <item></green> <dark_gray>| <gold><price></gold> <dark_gray>| <yellow><buyer>"
    empty: "<gray>No recorded sales yet."
```

- [ ] **Step 2: Inject the repo + add the subcommand**

In `ShopCommands.kt`, add `private val transactions: net.badgersmc.em.domain.shop.ShopTransactionRepository,` to the constructor. Add:
```kotlin
    @Subcommand("history")
    @Permission("enthusiamarket.shop.use")
    fun history(
        @Context sender: CommandSender,
        @net.badgersmc.nexus.commands.annotations.Arg("page") page: Int = 1,
    ) {
        val player = sender as? Player ?: run { sender.sendMessage(lang.msg("shop.cmd.players_only")); return }
        val p = page.coerceAtLeast(1)
        val rows = transactions.findByOwner(player.uniqueId, PAGE_SIZE, (p - 1) * PAGE_SIZE)
        if (rows.isEmpty()) { player.sendMessage(lang.msg("shop.history.empty")); return }
        player.sendMessage(lang.msg("shop.history.header", "page" to p))
        val fmt = java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm")
            .withZone(java.time.ZoneId.systemDefault())
        for (t in rows) {
            val buyer = org.bukkit.Bukkit.getOfflinePlayer(t.buyer).name ?: "Unknown"
            player.sendMessage(lang.msg(
                "shop.history.line",
                "when" to fmt.format(java.time.Instant.ofEpochMilli(t.createdAt)),
                "qty" to t.quantity, "item" to t.item, "price" to t.totalPrice, "buyer" to buyer,
            ))
        }
    }
```
Add a companion (or top-level const) `private const val PAGE_SIZE = 10` inside `ShopCommands` (if no companion exists, add `private companion object { const val PAGE_SIZE = 10 }`).

- [ ] **Step 3: Build (+ fix ShopCommands test ctor if present)**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew compileKotlin compileTestKotlin -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: BUILD SUCCESSFUL. If a `ShopCommands` test constructs it directly, add `mockk<ShopTransactionRepository>(relaxed = true)`.

- [ ] **Step 4: Commit**

```bash
cd /d/BadgersMC-Dev/EnthusiaMarket && git add src/main/kotlin/net/badgersmc/em/infrastructure/commands/ShopCommands.kt src/main/resources/lang/en_US.yml
git commit -m "feat(shop): /shop history paginated sales log (SP6)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 4: Owner sale notifications

**Files:**
- Modify: `src/main/kotlin/net/badgersmc/em/config/EnthusiaMarketConfig.kt`
- Create: `src/main/kotlin/net/badgersmc/em/infrastructure/listeners/ShopSaleNotifier.kt`
- Create: `src/main/kotlin/net/badgersmc/em/infrastructure/listeners/ShopSaleJoinNotifier.kt`
- Modify: `src/main/resources/lang/en_US.yml`

- [ ] **Step 1: Config flag**

In `EnthusiaMarketConfig.kt` `class Shop`, add:
```kotlin
        @Comment("Notify shop owners when someone trades at their shop (live if online, summarised on next join).")
        var notifyEnabled: Boolean = true
```

- [ ] **Step 2: Lang keys**

Under `shop:` add a `notify` block:
```yaml
  notify:
    sold: "<green>Sale: <white><qty>x <item></white> for <gold><price></gold> <gray>to</gray> <yellow><buyer>"
    away_summary: "<gold>While you were away, your shops made <white><count></white> sale(s). <gray>Use /shop history."
```

- [ ] **Step 3: Online notifier**

`ShopSaleNotifier.kt`:
```kotlin
package net.badgersmc.em.infrastructure.listeners

import net.badgersmc.em.config.EnthusiaMarketConfig
import net.badgersmc.em.domain.shop.ShopTransactionRepository
import net.badgersmc.em.events.PostShopTransactionEvent
import net.badgersmc.nexus.annotations.Component
import net.badgersmc.nexus.i18n.LangService
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener

/** Notifies an online shop owner of a sale and marks their pending rows seen (SP6). */
@net.badgersmc.nexus.paper.listeners.Listener
@Component
open class ShopSaleNotifier(
    private val transactions: ShopTransactionRepository,
    private val config: EnthusiaMarketConfig,
    private val lang: LangService,
) : Listener {

    @EventHandler
    fun onTransaction(event: PostShopTransactionEvent) {
        if (!config.shop.notifyEnabled) return
        val owner = Bukkit.getPlayer(event.landlordId) ?: return // offline → join notifier handles it
        owner.sendMessage(lang.msg(
            "shop.notify.sold",
            "qty" to event.quantity, "item" to event.item.type.name.lowercase(),
            "price" to event.pricePaid.toLong(), "buyer" to event.buyer.name,
        ))
        transactions.markNotified(event.landlordId)
    }
}
```

- [ ] **Step 4: Offline (join) notifier**

`ShopSaleJoinNotifier.kt`:
```kotlin
package net.badgersmc.em.infrastructure.listeners

import net.badgersmc.em.config.EnthusiaMarketConfig
import net.badgersmc.em.domain.shop.ShopTransactionRepository
import net.badgersmc.nexus.annotations.Component
import net.badgersmc.nexus.i18n.LangService
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent

/** On join, summarise sales the owner missed while offline, then mark them seen (SP6). */
@net.badgersmc.nexus.paper.listeners.Listener
@Component
open class ShopSaleJoinNotifier(
    private val transactions: ShopTransactionRepository,
    private val config: EnthusiaMarketConfig,
    private val lang: LangService,
) : Listener {

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        if (!config.shop.notifyEnabled) return
        val owner = event.player.uniqueId
        val unseen = transactions.countUnnotified(owner)
        if (unseen <= 0) return
        event.player.sendMessage(lang.msg("shop.notify.away_summary", "count" to unseen))
        transactions.markNotified(owner)
    }
}
```

- [ ] **Step 5: Build**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew compileKotlin -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
cd /d/BadgersMC-Dev/EnthusiaMarket && git add src/main/kotlin/net/badgersmc/em/config/EnthusiaMarketConfig.kt src/main/kotlin/net/badgersmc/em/infrastructure/listeners/ShopSaleNotifier.kt src/main/kotlin/net/badgersmc/em/infrastructure/listeners/ShopSaleJoinNotifier.kt src/main/resources/lang/en_US.yml
git commit -m "feat(shop): owner sale notifications, online + on-join (SP6)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 5: PAPI placeholders

**Files:**
- Modify: `build.gradle.kts`
- Create: `src/main/kotlin/net/badgersmc/em/infrastructure/papi/ShopPlaceholders.kt`
- Modify: `src/main/kotlin/net/badgersmc/em/EnthusiaMarket.kt`
- Test: `src/test/kotlin/net/badgersmc/em/infrastructure/papi/ShopPlaceholdersTest.kt`

- [ ] **Step 1: Add the nexus-papi dependency**

In `build.gradle.kts`, next to the other `nexus-*` lines, add:
```kotlin
    implementation("com.github.BadgersMC.Nexus:nexus-papi:v2.2.1")
```

- [ ] **Step 2: Failing resolver test**

`ShopPlaceholdersTest.kt`:
```kotlin
package net.badgersmc.em.infrastructure.papi

import io.mockk.every
import io.mockk.mockk
import net.badgersmc.em.domain.shop.Shop
import net.badgersmc.em.domain.shop.ShopRepository
import net.badgersmc.em.domain.shop.ShopTransactionRepository
import org.bukkit.OfflinePlayer
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ShopPlaceholdersTest {

    private val uuid = UUID.randomUUID()
    private val player = mockk<OfflinePlayer> { every { uniqueId } returns uuid }

    private fun shops(repo: ShopRepository, tx: ShopTransactionRepository) = ShopPlaceholders(repo, tx)

    @Test fun `shops_owned counts the player's shops`() {
        val repo = mockk<ShopRepository> { every { findByOwner(uuid) } returns listOf(mockk<Shop>(), mockk<Shop>()) }
        val tx = mockk<ShopTransactionRepository>(relaxed = true)
        assertEquals("2", shops(repo, tx).resolve(player, "shops_owned"))
    }

    @Test fun `sales_unseen reads the tx repo`() {
        val repo = mockk<ShopRepository>(relaxed = true)
        val tx = mockk<ShopTransactionRepository> { every { countUnnotified(uuid) } returns 3 }
        assertEquals("3", shops(repo, tx).resolve(player, "sales_unseen"))
    }

    @Test fun `unknown key returns null`() {
        assertNull(shops(mockk(relaxed = true), mockk(relaxed = true)).resolve(player, "nope"))
    }

    @Test fun `player-scoped key with null player returns null`() {
        assertNull(shops(mockk(relaxed = true), mockk(relaxed = true)).resolve(null, "shops_owned"))
    }
}
```

- [ ] **Step 3: Run the test — verify it fails**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew test --tests "net.badgersmc.em.infrastructure.papi.ShopPlaceholdersTest" -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: FAIL — `ShopPlaceholders` not defined.

- [ ] **Step 4: Implement the expansion**

`ShopPlaceholders.kt`:
```kotlin
package net.badgersmc.em.infrastructure.papi

import net.badgersmc.em.domain.shop.ShopRepository
import net.badgersmc.em.domain.shop.ShopTransactionRepository
import net.badgersmc.nexus.annotations.Component
import net.badgersmc.nexus.papi.PapiExpansion
import net.badgersmc.nexus.papi.PlaceholderResolver
import org.bukkit.OfflinePlayer

/** %enthusiamarket_<key>% placeholders (ItemShops parity SP6). */
@PapiExpansion(identifier = "enthusiamarket")
@Component
class ShopPlaceholders(
    private val shops: ShopRepository,
    private val transactions: ShopTransactionRepository,
) : PlaceholderResolver {

    override fun resolve(player: OfflinePlayer?, params: String): String? = when (params.lowercase()) {
        "shops_total" -> shops.all().size.toString()
        "shops_owned" -> player?.let { shops.findByOwner(it.uniqueId).size.toString() }
        "sales_unseen" -> player?.let { transactions.countUnnotified(it.uniqueId).toString() }
        else -> null
    }
}
```

- [ ] **Step 5: Register in onEnable**

In `EnthusiaMarket.kt` `onEnable`, after the Nexus context `ctx` is created (and after listeners are registered is fine), add:
```kotlin
        // PlaceholderAPI expansions (no-ops if PAPI absent).
        net.badgersmc.nexus.papi.registerNexusExpansions(
            basePackage = "net.badgersmc.em",
            classLoader = this::class.java.classLoader,
            nexus = ctx,
        )
```

- [ ] **Step 6: Run the test — verify it passes + build**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew test --tests "net.badgersmc.em.infrastructure.papi.ShopPlaceholdersTest" compileKotlin -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: PASS + BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
cd /d/BadgersMC-Dev/EnthusiaMarket && git add build.gradle.kts src/main/kotlin/net/badgersmc/em/infrastructure/papi/ShopPlaceholders.kt src/main/kotlin/net/badgersmc/em/EnthusiaMarket.kt src/test/kotlin/net/badgersmc/em/infrastructure/papi/ShopPlaceholdersTest.kt
git commit -m "feat(shop): PlaceholderAPI expansion (shops_owned/total/sales_unseen) (SP6)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 6: Sign-click info card

**Files:**
- Create: `src/main/kotlin/net/badgersmc/em/interaction/ShopInfoCard.kt`
- Modify: `src/main/kotlin/net/badgersmc/em/infrastructure/listeners/ShopInteractListener.kt`
- Modify: `src/main/resources/lang/en_US.yml`
- Test: `src/test/kotlin/net/badgersmc/em/interaction/ShopInfoCardTest.kt`

- [ ] **Step 1: Lang keys**

Under `shop:` add an `info` block:
```yaml
  info:
    line1: "<gold>━━ Shop <gray>(<dir>) ━━"
    line2: "<gray>Item: <white><qty>x <item>"
    line3: "<gray>Price: <gold><price>"
    line4: "<gray>Owner: <yellow><owner></yellow>  <dark_gray>|  <gray>Stock: <aqua><stock>"
```

- [ ] **Step 2: Failing test for the pure card builder**

`ShopInfoCardTest.kt`:
```kotlin
package net.badgersmc.em.interaction

import io.mockk.every
import io.mockk.mockk
import net.badgersmc.nexus.i18n.LangService
import net.kyori.adventure.text.Component
import kotlin.test.Test
import kotlin.test.assertEquals

class ShopInfoCardTest {

    @Test fun `builds four lines from shop data`() {
        val lang = mockk<LangService>()
        every { lang.msg(any(), *anyVararg()) } returns Component.empty()
        val lines = ShopInfoCard.lines(
            lang, direction = "SELL", item = "diamond", qty = 5, price = 100L, owner = "Steve", stock = 8,
        )
        assertEquals(4, lines.size)
    }
}
```
(If `any()`/`anyVararg()` import friction arises, assert on a single concrete `lang.msg` stub instead — the point is that `lines` returns 4 components.)

- [ ] **Step 3: Run — verify it fails**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew test --tests "net.badgersmc.em.interaction.ShopInfoCardTest" -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: FAIL — `ShopInfoCard` not defined.

- [ ] **Step 4: Implement the builder**

`ShopInfoCard.kt`:
```kotlin
package net.badgersmc.em.interaction

import net.badgersmc.nexus.i18n.LangService
import net.kyori.adventure.text.Component

/** Pure builder for the shift-right-click shop info card (ItemShops parity SP6). */
object ShopInfoCard {
    fun lines(
        lang: LangService, direction: String, item: String, qty: Int, price: Long, owner: String, stock: Int,
    ): List<Component> = listOf(
        lang.msg("shop.info.line1", "dir" to direction),
        lang.msg("shop.info.line2", "qty" to qty, "item" to item),
        lang.msg("shop.info.line3", "price" to price),
        lang.msg("shop.info.line4", "owner" to owner, "stock" to stock),
    )
}
```

- [ ] **Step 5: Branch in ShopInteractListener**

Read `ShopInteractListener.kt`. After the shop is resolved and `event.isCancelled = true` is set, before the menu opens, add:
```kotlin
        if (player.isSneaking) {
            val owner = org.bukkit.Bukkit.getOfflinePlayer(shop.owner).name ?: "Unknown"
            val item = net.badgersmc.em.application.ItemStackSerializer.deserialize(shop.sellItem)?.type?.name?.lowercase() ?: "?"
            ShopInfoCard.lines(
                lang, shop.direction.name, item, shop.sellAmount, shop.costAmount.toLong(), owner, stockOf(shop),
            ).forEach { player.sendMessage(it) }
            return
        }
```
Add a private helper mirroring `SearchResultsMenu.tradesAvailable` (live container stock / sellAmount):
```kotlin
    private fun stockOf(shop: net.badgersmc.em.domain.shop.Shop): Int {
        val world = org.bukkit.Bukkit.getWorld(shop.containerWorld) ?: return 0
        val state = world.getBlockAt(shop.containerX, shop.containerY, shop.containerZ).state
        val inv = (state as? org.bukkit.block.Container)?.inventory ?: return 0
        val sellStack = net.badgersmc.em.application.ItemStackSerializer.deserialize(shop.sellItem) ?: return 0
        val total = inv.contents.filterNotNull().filter { it.isSimilar(sellStack) }.sumOf { it.amount }
        return total / shop.sellAmount.coerceAtLeast(1)
    }
```
(`player` and `lang` are already in scope / injected in this listener.)

- [ ] **Step 6: Run the test + build**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew test --tests "net.badgersmc.em.interaction.ShopInfoCardTest" compileKotlin -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: PASS + BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
cd /d/BadgersMC-Dev/EnthusiaMarket && git add src/main/kotlin/net/badgersmc/em/interaction/ShopInfoCard.kt src/main/kotlin/net/badgersmc/em/infrastructure/listeners/ShopInteractListener.kt src/main/resources/lang/en_US.yml src/test/kotlin/net/badgersmc/em/interaction/ShopInfoCardTest.kt
git commit -m "feat(shop): shift-right-click shop info card (SP6)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 7: Retention pruning

**Files:**
- Modify: `src/main/kotlin/net/badgersmc/em/config/EnthusiaMarketConfig.kt`
- Modify: `src/main/kotlin/net/badgersmc/em/EnthusiaMarket.kt`

- [ ] **Step 1: Config**

In `EnthusiaMarketConfig.kt` `class Shop`, add:
```kotlin
        @Comment("Days of shop transaction history to keep; 0 disables pruning.")
        var historyRetentionDays: Int = 30
```

- [ ] **Step 2: Prune on enable**

In `EnthusiaMarket.kt` `onEnable`, after the DI context + dataSource exist (after Phase 6 listener registration is fine), add:
```kotlin
        // Prune old shop transaction history per config (0 = keep everything).
        if (cfg.shop.historyRetentionDays > 0) {
            val txRepo = ctx.getBean<net.badgersmc.em.domain.shop.ShopTransactionRepository>()
            val cutoff = System.currentTimeMillis() - cfg.shop.historyRetentionDays.toLong() * 86_400_000L
            val pruned = txRepo.prune(cutoff)
            if (pruned > 0) logger.info("Pruned $pruned old shop transaction(s)")
        }
```
(`cfg` is the `EnthusiaMarketConfig` already fetched in `onEnable`; `ctx.getBean<T>()` is the existing accessor.)

- [ ] **Step 3: Build**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew compileKotlin -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
cd /d/BadgersMC-Dev/EnthusiaMarket && git add src/main/kotlin/net/badgersmc/em/config/EnthusiaMarketConfig.kt src/main/kotlin/net/badgersmc/em/EnthusiaMarket.kt
git commit -m "feat(shop): prune shop transaction history per retention config (SP6)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 8: Final gate

- [ ] **Step 1: Full verification on the committed HEAD**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew clean detekt test shadowJar -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: BUILD SUCCESSFUL, detekt 0, all tests pass.

- [ ] **Step 2: Mark progress + commit**

Append to `docs/tasks.md`: `- [x] ItemShops parity SP6 — misc/integration (transaction log + /shop history, owner notifications, PAPI placeholders, sign-click info card)`. Then:
```bash
cd /d/BadgersMC-Dev/EnthusiaMarket && git add docs/tasks.md
git commit -m "docs: mark ItemShops parity SP6 (misc/integration) complete

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

- [ ] **Step 3: Report**

Report the final gate output + commit list. Do NOT push.

---

## Self-Review Notes (for the implementer)

1. **Transaction table is SQLite syntax** (Task 1) — `INTEGER PRIMARY KEY AUTOINCREMENT`, matching `shop_items`. The persistence test (MockBukkit + real migration) is the gate.
2. **Event back-compat** (Task 2) — the two new `PostShopTransactionEvent` params are defaulted, so any existing construction still compiles; only `ContainerTradeService` passes the real values.
3. **History is best-effort** (Task 2) — the recorder swallows DB errors; a logging failure must never roll back a completed trade.
4. **The `notified` flag is the single unseen-queue** (Task 4) — the online notifier marks-notified after sending, so the join notifier won't repeat it. Both early-return when `notifyEnabled = false`.
5. **`event.buyer.name`** (Task 4 online notifier) — `buyer` is an online `Player` (the clicker), so `.name` is safe there; the join/history paths use `OfflinePlayer` name with an "Unknown" fallback.
6. **PAPI is provide-side only** (Task 5) — `registerNexusExpansions` no-ops without PlaceholderAPI; never assume the placeholder is installed. New dep `nexus-papi:v2.2.1` must match the other Nexus modules' version.
7. **Info card reuses the stock read** (Task 6) — same container-inventory logic as `SearchResultsMenu.tradesAvailable`; keep it a private helper in the listener (don't over-abstract for SP6).
8. **Prune needs the repo bean** (Task 7) — `ctx.getBean<ShopTransactionRepository>()` only works after the Nexus context + `dataSource` bean exist; place the call late in `onEnable`.
9. **Constructor churn** (Tasks 2–7) — `ContainerTradeService`, `ShopCommands`, `ShopInteractListener` gain deps/params. Update any direct-construction test with the new args; Nexus injects them in production.
