# Barter / Profits Vault Implementation Plan (ItemShops Parity SP3)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Add a `[TRADE]` sign keyword for item-for-item shops alongside the money `[SELL]`/`[BUY]` shops. The buyer pays `N×costItem` (into the owner's per-owner **vault**), receives `M×sellItem` from the chest. Owner withdraws via `/shopvault`. Guild-owned stalls cannot host `[TRADE]`.

**Architecture:** Hexagonal/SPEAR. New persistence aggregate (`ShopVault`, migration V016) using Paper's NBT `serializeAsBytes`; a new `executeTrade` path on `ContainerTradeService`; a new `SignDirection.TRADE`.

**Tech Stack:** Kotlin 2.0.0, Paper 1.21.11 API, Nexus DI + commands + persistence, IFramework GUIs, JUnit 5 + MockK + MockBukkit, detekt 1.23.8.

**Reference spec:** `docs/superpowers/specs/2026-06-04-itemshops-parity-barter-vault-design.md`

**Standing rules (every task):**
- Prefix bash with `cd <REPO> &&` (`/d/BadgersMC-Dev/EnthusiaMarket`, or `/opt/data/EnthusiaMarket`). On Hermes' box also prefix gradle with `export JAVA_HOME=/opt/data/jdk-21.0.11+10 &&`.
- Every gradle command includes `-Plumaguilds.jar=<JAR> --no-daemon --console=plain`.
- LF→CRLF git warnings expected. Branch `feat/barter-vault`. Do not push (coordinator opens the PR).
- TDD: write failing test, run RED, then GREEN. Commit after every task with the given message.
- Gate rule: compare each `Run:` to `Expected:`; mismatch → STOP, fix, re-run; HALT after 3 tries. Final gate runs on the EXACT committed HEAD.

---

## CONFIRMED API SYMBOLS (verified against the repo — use exactly)

- **Paper item serialization (Paper 1.21.11):** instance `itemStack.serializeAsBytes(): ByteArray` (NBT, data-converter-safe); static `org.bukkit.inventory.ItemStack.deserializeBytes(bytes: ByteArray): ItemStack`. Base64 via `java.util.Base64`. **Use these for the vault only** — `Shop.sellItem`/`costItem` stay on `ItemStackSerializer`.
- **`SignDirection`** (`domain/shop/SignDirection.kt`) — currently `enum { BUY, SELL }`. Add `TRADE`. Stored as text via the existing `direction` column (V012); `SignDirection.valueOf("TRADE")` round-trips.
- **`when (direction)` sites that become non-exhaustive when TRADE is added (must get a branch):**
  - `interaction/gui/PurchaseMenu.kt` ~L79: `when (shop.direction) { SELL -> executeSell; BUY -> executeBuy }` → add `TRADE -> tradeService.executeTrade(shop, player.uniqueId)`. Also its button-label `if (shop.direction == SignDirection.SELL) "gui.shop.buy_name" else "gui.shop.sell_action_name"` → give TRADE its own label (`gui.shop.trade_name`/`gui.shop.trade_lore_click`).
  - `application/ShopTradeService.kt` ~L74: legacy sign `when (...) { BUY -> ...; SELL -> ... }` → add `TRADE -> ContainerTradeResult`-equivalent failure (this legacy path doesn't handle barter; return its failure type so it compiles). Read the file; match its return type.
  - `application/ShopSignRenderer.kt` L17: color `if (direction == SignDirection.BUY) GOLD else AQUA` → add TRADE → `NamedTextColor.LIGHT_PURPLE` (see Task 6, which also changes the signature).
- **`Shop`** (`domain/shop/Shop.kt`) — `id, owner: UUID, direction, sellItem(base64), sellAmount, costItem(base64), costAmount, signWorld/X/Y/Z, containerWorld/X/Y/Z, frozen`. `init` requires `sellAmount>0`, `costAmount>0`.
- **`ContainerTradeService`** (`application/ContainerTradeService.kt`) — `open class(stallRepository: StallRepository, economy: EconomyProvider, guildProvider: GuildProvider?)`. **Add `vaultService: ShopVaultService`**. Result type `sealed ContainerTradeResult { Success(message); Failure(reason); CompensationFailed(error, compensation) }`. Existing private/protected helpers to reuse: `buildSellStack(shop): ItemStack?`, `deserializeStack(base64): ItemStack?` (protected open), `getContainer(shop): Container?` (protected open), `getPlayer(uuid): Player?` (protected open), `resolveOwnerUuid(stall): UUID?` (SOLO → uuid, GUILD/NONE → null), `fireTransactionEvent(player, ownerUuid, item, quantity, cost, shopId, direction)` (SP6-extended). Pattern: `executeSell`/`executeBuy` (preconditions → transaction with rollback).
- **`StallRepository.findById(StallId): Stall?`**; **`Stall.owner: OwnerRef`**, **`OwnerRef.type: OwnerType`**, `OwnerType { NONE, SOLO, GUILD }` (`domain/stall/`).
- **`PurchaseMenu`** (`interaction/gui/PurchaseMenu.kt`) — `(shop, tradeService: ContainerTradeService, lang)`; has `decorated(material, name, lore)` helper; cost slot already renders from `shop.costItem`/`costAmount`.
- **`SignPlaceListener`** (`infrastructure/listeners/SignPlaceListener.kt`) — `firstLine` `when` maps `[SELL]/[BUY]` → direction; line 2 = amount (`toIntOrNull`), line 3 = price (`toLongOrNull`); builds `Shop(...)` with `searchEnabled = config.shop.searchDefault`; calls `signRenderer.lines(direction, held.type.name.lowercase(), amount, price)`. Injects `stallRepository`, `guildProvider`, `config`, `signRenderer`. `findStallAt(loc): Stall?` already used; check `stall.owner.type == OwnerType.GUILD` to reject TRADE. `Material.matchMaterial(name): Material?`.
- **`ShopEditMenu`** (`interaction/gui/ShopEditMenu.kt`) — cost section at pane slots `(4,0)+ (4,1)EMERALD (4,2)-`; `save` button calls `ShopEditMenu.applyEdits(shop, sellItemB64, sellAmount, costAmount, hopperIn, hopperOut, frozen, searchEnabled)`; has `decorated(...)`. For TRADE shops, the cost section sets `costItem` from hand + qty.
- **Nexus command** — top-level command = `@Command(name="shopvault") class VaultCommands(...)` with `@Subcommand`/default; mirror `ShopCommands`. Discovered by the Paper command scanner. `@Permission`, `@Context sender: CommandSender`.
- **`@Repository`** + `DataSource` ctor; SQLite; migrations dir `src/main/resources/migrations/`, next number **V016**. SQLite supports `INSERT ... ON CONFLICT(...) DO UPDATE SET ... = excluded....`.
- **IFramework GUI** — mirror `SearchResultsMenu` (paginated `ChestGui(6, ...)`, `StaticPane(9,6)`, `GuiItem(icon){ it.isCancelled = true; ... }`, prev/next arrows).
- **`LangService`** — `lang.msg("key", "tok" to v)`; `<token>` placeholders. Lang file top-level `shop:` and `gui:` blocks; reuse `shop.cmd.players_only`.
- **Permission DSL** (`build.gradle.kts`) — `node("enthusiamarket.shop.vault", default = Default.TRUE, description = "...")`.

---

## Task 1: SignDirection.TRADE + keep compilation exhaustive

**Files:**
- Modify: `src/main/kotlin/net/badgersmc/em/domain/shop/SignDirection.kt`
- Modify: `src/main/kotlin/net/badgersmc/em/interaction/gui/PurchaseMenu.kt`
- Modify: `src/main/kotlin/net/badgersmc/em/application/ShopTradeService.kt`
- Modify: `src/main/kotlin/net/badgersmc/em/application/ShopSignRenderer.kt`

- [ ] **Step 1: Add the enum value**

```kotlin
enum class SignDirection { BUY, SELL, TRADE }
```

- [ ] **Step 2: Make the `when`/`if` sites compile**

Read each file first. Minimal, compile-only changes (real wiring lands in later tasks):
- `PurchaseMenu` result `when`: add `SignDirection.TRADE -> tradeService.executeTrade(shop, player.uniqueId)`. (`executeTrade` is added in Task 4; until then this won't resolve — so **do Task 1's PurchaseMenu edit as part of Task 4 instead** if it blocks compilation. For Task 1, add `SignDirection.TRADE -> tradeService.executeSell(shop, player.uniqueId)` as a temporary placeholder ONLY if needed to compile, and note it; Task 4 replaces it.) Prefer: leave PurchaseMenu for Task 4 and instead add an explicit `else -> ` guard is NOT allowed (enum exhaustiveness). **Simplest: implement `executeTrade` stub in Task 4's file early** — but to keep Task 1 self-contained, route `TRADE` to a `ContainerTradeResult.Failure(lang.msg(...))`-style placeholder is awkward in the menu. Decision: in Task 1, add the `TRADE` branch to `PurchaseMenu` calling a new no-op `tradeService.executeTrade(...)` and create a **minimal `executeTrade` stub** returning `ContainerTradeResult.Failure("barter not wired yet")` in `ContainerTradeService` now; Task 4 fills the body.
- `ShopTradeService` legacy `when`: add `SignDirection.TRADE -> <its failure result>` (read the file for the exact type/shape; this legacy service is not used for barter).
- `ShopSignRenderer` color `if`: change to `when (direction) { SignDirection.BUY -> NamedTextColor.GOLD; SignDirection.TRADE -> NamedTextColor.LIGHT_PURPLE; else -> NamedTextColor.AQUA }`.

- [ ] **Step 3: Build**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew compileKotlin compileTestKotlin -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
cd /d/BadgersMC-Dev/EnthusiaMarket && git add -A
git commit -m "feat(shop): SignDirection.TRADE + exhaustive when sites (SP3)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 2: ShopVault domain + V016 + repository

**Files:**
- Create: `src/main/kotlin/net/badgersmc/em/domain/shop/VaultItem.kt`
- Create: `src/main/kotlin/net/badgersmc/em/domain/shop/ShopVaultRepository.kt`
- Create: `src/main/resources/migrations/V016__shop_vault.sql`
- Create: `src/main/kotlin/net/badgersmc/em/infrastructure/persistence/ShopVaultRepositorySql.kt`
- Test: `src/test/kotlin/net/badgersmc/em/infrastructure/persistence/ShopVaultRepositorySqlTest.kt`

- [ ] **Step 1: Domain types**

`VaultItem.kt`:
```kotlin
package net.badgersmc.em.domain.shop

import java.util.UUID

/** A stack of collected barter payment held in an owner's vault (ItemShops parity SP3). */
data class VaultItem(val owner: UUID, val itemBytes: String /* Base64 NBT */, val amount: Int)
```

`ShopVaultRepository.kt`:
```kotlin
package net.badgersmc.em.domain.shop

import java.util.UUID

interface ShopVaultRepository {
    fun deposit(owner: UUID, itemBytes: String, amount: Int)
    fun findByOwner(owner: UUID): List<VaultItem>
    /** Remove up to [amount]; deletes the row at zero. Returns amount actually removed. */
    fun withdraw(owner: UUID, itemBytes: String, amount: Int): Int
}
```

- [ ] **Step 2: Migration V016**

`src/main/resources/migrations/V016__shop_vault.sql`:
```sql
-- ItemShops parity SP3 — per-owner barter profits vault. Payment items from [TRADE]
-- shops accumulate here, aggregated by NBT-serialized item key, withdrawn via /shopvault.
CREATE TABLE IF NOT EXISTS shop_vault (
    owner   TEXT NOT NULL,
    item    TEXT NOT NULL,
    amount  INTEGER NOT NULL,
    PRIMARY KEY (owner, item)
);
CREATE INDEX IF NOT EXISTS idx_shop_vault_owner ON shop_vault(owner);
```

- [ ] **Step 3: Failing persistence test**

`ShopVaultRepositorySqlTest.kt`:
```kotlin
package net.badgersmc.em.infrastructure.persistence

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

class ShopVaultRepositorySqlTest {

    private val dbFile = File.createTempFile("em-vault", ".db")
    private lateinit var ds: DataSource

    @BeforeTest fun setup() {
        MockBukkit.mock()
        ds = DatabaseFactory.open(DatabaseSpec.Sqlite(dbFile)).also {
            MigrationRunner(it, resourcePrefix = "migrations", classLoader = javaClass.classLoader).runAll()
        }
    }

    @AfterTest fun teardown() { MockBukkit.unmock(); dbFile.delete() }

    @Test fun `deposit aggregates the same item key`() {
        val repo = ShopVaultRepositorySql(ds)
        val owner = UUID.randomUUID()
        repo.deposit(owner, "DIAMOND_KEY", 5)
        repo.deposit(owner, "DIAMOND_KEY", 3)
        val rows = repo.findByOwner(owner)
        assertEquals(1, rows.size)
        assertEquals(8, rows.first().amount)
    }

    @Test fun `different items are separate rows`() {
        val repo = ShopVaultRepositorySql(ds)
        val owner = UUID.randomUUID()
        repo.deposit(owner, "A", 1); repo.deposit(owner, "B", 1)
        assertEquals(2, repo.findByOwner(owner).size)
    }

    @Test fun `withdraw decrements and deletes at zero`() {
        val repo = ShopVaultRepositorySql(ds)
        val owner = UUID.randomUUID()
        repo.deposit(owner, "A", 10)
        assertEquals(4, repo.withdraw(owner, "A", 4))
        assertEquals(6, repo.findByOwner(owner).first().amount)
        assertEquals(6, repo.withdraw(owner, "A", 100)) // only 6 left
        assertEquals(0, repo.findByOwner(owner).size)
    }
}
```

- [ ] **Step 4: Run — verify it fails**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew test --tests "net.badgersmc.em.infrastructure.persistence.ShopVaultRepositorySqlTest" -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: FAIL — `ShopVaultRepositorySql` not defined.

- [ ] **Step 5: Implement the repository**

`ShopVaultRepositorySql.kt`:
```kotlin
package net.badgersmc.em.infrastructure.persistence

import net.badgersmc.em.domain.shop.ShopVaultRepository
import net.badgersmc.em.domain.shop.VaultItem
import net.badgersmc.nexus.annotations.Repository
import java.util.UUID
import javax.sql.DataSource

@Repository
class ShopVaultRepositorySql(private val ds: DataSource) : ShopVaultRepository {

    override fun deposit(owner: UUID, itemBytes: String, amount: Int) {
        ds.connection.use { c ->
            c.prepareStatement(
                """INSERT INTO shop_vault (owner, item, amount) VALUES (?, ?, ?)
                   ON CONFLICT(owner, item) DO UPDATE SET amount = amount + excluded.amount"""
            ).use { ps ->
                ps.setString(1, owner.toString()); ps.setString(2, itemBytes); ps.setInt(3, amount)
                ps.executeUpdate()
            }
        }
    }

    override fun findByOwner(owner: UUID): List<VaultItem> {
        ds.connection.use { c ->
            c.prepareStatement("SELECT item, amount FROM shop_vault WHERE owner = ? ORDER BY item").use { ps ->
                ps.setString(1, owner.toString())
                ps.executeQuery().use { rs ->
                    val out = mutableListOf<VaultItem>()
                    while (rs.next()) out += VaultItem(owner, rs.getString("item"), rs.getInt("amount"))
                    return out
                }
            }
        }
    }

    override fun withdraw(owner: UUID, itemBytes: String, amount: Int): Int {
        ds.connection.use { c ->
            val current = c.prepareStatement("SELECT amount FROM shop_vault WHERE owner = ? AND item = ?").use { ps ->
                ps.setString(1, owner.toString()); ps.setString(2, itemBytes)
                ps.executeQuery().use { rs -> if (rs.next()) rs.getInt(1) else 0 }
            }
            if (current <= 0) return 0
            val remove = minOf(current, amount)
            if (remove >= current) {
                c.prepareStatement("DELETE FROM shop_vault WHERE owner = ? AND item = ?").use { ps ->
                    ps.setString(1, owner.toString()); ps.setString(2, itemBytes); ps.executeUpdate()
                }
            } else {
                c.prepareStatement("UPDATE shop_vault SET amount = amount - ? WHERE owner = ? AND item = ?").use { ps ->
                    ps.setInt(1, remove); ps.setString(2, owner.toString()); ps.setString(3, itemBytes); ps.executeUpdate()
                }
            }
            return remove
        }
    }
}
```

- [ ] **Step 6: Run — verify it passes**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew test --tests "net.badgersmc.em.infrastructure.persistence.ShopVaultRepositorySqlTest" -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
cd /d/BadgersMC-Dev/EnthusiaMarket && git add src/main/kotlin/net/badgersmc/em/domain/shop/VaultItem.kt src/main/kotlin/net/badgersmc/em/domain/shop/ShopVaultRepository.kt src/main/resources/migrations/V016__shop_vault.sql src/main/kotlin/net/badgersmc/em/infrastructure/persistence/ShopVaultRepositorySql.kt src/test/kotlin/net/badgersmc/em/infrastructure/persistence/ShopVaultRepositorySqlTest.kt
git commit -m "feat(shop): ShopVault domain + V016 + repository (SP3)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 3: ShopVaultService (Paper NBT serialization)

**Files:**
- Create: `src/main/kotlin/net/badgersmc/em/application/ShopVaultService.kt`
- Test: `src/test/kotlin/net/badgersmc/em/application/ShopVaultServiceTest.kt`

- [ ] **Step 1: Failing test (MockBukkit — serializeAsBytes needs a server)**

`ShopVaultServiceTest.kt`:
```kotlin
package net.badgersmc.em.application

import net.badgersmc.em.domain.shop.ShopVaultRepository
import net.badgersmc.em.domain.shop.VaultItem
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.mockbukkit.mockbukkit.MockBukkit
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ShopVaultServiceTest {

    @BeforeTest fun setup() { MockBukkit.mock() }
    @AfterTest fun teardown() { MockBukkit.unmock() }

    /** Tiny in-memory ShopVaultRepository fake. */
    private class FakeVault : ShopVaultRepository {
        val store = mutableMapOf<Pair<UUID, String>, Int>()
        override fun deposit(owner: UUID, itemBytes: String, amount: Int) {
            store[owner to itemBytes] = (store[owner to itemBytes] ?: 0) + amount
        }
        override fun findByOwner(owner: UUID) =
            store.filterKeys { it.first == owner }.map { VaultItem(owner, it.key.second, it.value) }
        override fun withdraw(owner: UUID, itemBytes: String, amount: Int): Int {
            val cur = store[owner to itemBytes] ?: return 0
            val r = minOf(cur, amount)
            if (r >= cur) store.remove(owner to itemBytes) else store[owner to itemBytes] = cur - r
            return r
        }
    }

    @Test fun `deposit then contents round-trips the item`() {
        val repo = FakeVault()
        val svc = ShopVaultService(repo)
        val owner = UUID.randomUUID()
        svc.deposit(owner, ItemStack(Material.DIAMOND), 7)
        val contents = svc.contents(owner)
        assertEquals(1, contents.size)
        assertEquals(Material.DIAMOND, contents.first().first.type)
        assertEquals(7, contents.first().second)
    }

    @Test fun `withdraw returns the amount removed`() {
        val repo = FakeVault()
        val svc = ShopVaultService(repo)
        val owner = UUID.randomUUID()
        svc.deposit(owner, ItemStack(Material.IRON_INGOT), 10)
        assertEquals(4, svc.withdraw(owner, ItemStack(Material.IRON_INGOT), 4))
    }
}
```

- [ ] **Step 2: Run — verify it fails**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew test --tests "net.badgersmc.em.application.ShopVaultServiceTest" -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: FAIL — `ShopVaultService` not defined.

- [ ] **Step 3: Implement the service**

`ShopVaultService.kt`:
```kotlin
package net.badgersmc.em.application

import net.badgersmc.em.domain.shop.ShopVaultRepository
import net.badgersmc.nexus.annotations.Service
import org.bukkit.inventory.ItemStack
import java.util.Base64
import java.util.UUID

/**
 * Per-owner barter vault (ItemShops parity SP3). Items are keyed by their Paper NBT
 * serialization (serializeAsBytes → Base64) — data-component-aware and migrated on
 * read via deserializeBytes, unlike the legacy ItemStackSerializer.
 */
@Service
class ShopVaultService(private val vault: ShopVaultRepository) {

    fun deposit(owner: UUID, item: ItemStack, amount: Int) = vault.deposit(owner, key(item), amount)

    fun withdraw(owner: UUID, item: ItemStack, amount: Int): Int = vault.withdraw(owner, key(item), amount)

    fun contents(owner: UUID): List<Pair<ItemStack, Int>> =
        vault.findByOwner(owner).map { ItemStack.deserializeBytes(Base64.getDecoder().decode(it.itemBytes)) to it.amount }

    private fun key(item: ItemStack): String {
        val one = item.clone().apply { amount = 1 }
        return Base64.getEncoder().encodeToString(one.serializeAsBytes())
    }
}
```

- [ ] **Step 4: Run — verify it passes**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew test --tests "net.badgersmc.em.application.ShopVaultServiceTest" -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
cd /d/BadgersMC-Dev/EnthusiaMarket && git add src/main/kotlin/net/badgersmc/em/application/ShopVaultService.kt src/test/kotlin/net/badgersmc/em/application/ShopVaultServiceTest.kt
git commit -m "feat(shop): ShopVaultService with Paper NBT serialization (SP3)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 4: ContainerTradeService.executeTrade (barter path)

**Files:**
- Modify: `src/main/kotlin/net/badgersmc/em/application/ContainerTradeService.kt`
- Test: `src/test/kotlin/net/badgersmc/em/application/ContainerTradeServiceTradeTest.kt`

- [ ] **Step 1: Inject the vault + add executeTrade (replace the Task 1 stub)**

Read `ContainerTradeService.kt`. Add `private val vaultService: ShopVaultService,` to the constructor. Implement `executeTrade` mirroring `executeSell`'s structure (preconditions then a rollback-guarded transaction), with NO economy:

```kotlin
    fun executeTrade(shop: Shop, playerUuid: UUID): ContainerTradeResult {
        if (shop.frozen) return ContainerTradeResult.Failure("This shop is frozen")
        if (shop.sellAmount <= 0 || shop.costAmount <= 0) return ContainerTradeResult.Failure("Invalid trade amounts")
        val stall = stallRepository.findById(StallId(shop.stallId))
            ?: return ContainerTradeResult.Failure("Stall not found")
        val ownerUuid = resolveOwnerUuid(stall) ?: return ContainerTradeResult.Failure("Invalid owner")
        val player = getPlayer(playerUuid) ?: return ContainerTradeResult.Failure("Player not online")
        val sellStack = buildSellStack(shop) ?: return ContainerTradeResult.Failure("Invalid item")
        val costBase = deserializeStack(shop.costItem) ?: return ContainerTradeResult.Failure("Invalid cost item")
        val costStack = costBase.clone().apply { amount = shop.costAmount }
        if (!player.inventory.containsAtLeast(costStack, shop.costAmount))
            return ContainerTradeResult.Failure("You don't have the items to pay")
        val container = getContainer(shop) ?: return ContainerTradeResult.Failure("Container missing")
        val inv = container.inventory
        if (!inv.containsAtLeast(sellStack, shop.sellAmount)) return ContainerTradeResult.Failure("Out of stock")

        // Execute: take payment from buyer.
        if (player.inventory.removeItem(costStack.clone()).isNotEmpty())
            return ContainerTradeResult.Failure("Not enough payment items")
        vaultService.deposit(ownerUuid, costBase.clone().apply { amount = 1 }, shop.costAmount)

        // Give stock to buyer.
        inv.removeItem(sellStack.clone())
        if (player.inventory.addItem(sellStack.clone()).isNotEmpty()) {
            // Rollback: stock back to chest, payment back to buyer, undo the vault deposit.
            inv.addItem(sellStack)
            vaultService.withdraw(ownerUuid, costBase.clone().apply { amount = 1 }, shop.costAmount)
            player.inventory.addItem(costStack)
            return ContainerTradeResult.CompensationFailed(error = "Inventory full", compensation = "Trade reversed")
        }

        fireTransactionEvent(player, ownerUuid, sellStack, shop.sellAmount, 0L, shop.id, shop.direction)
        return ContainerTradeResult.Success("Traded ${shop.sellAmount}x for ${shop.costAmount}x")
    }
```

(`StallId` import: `net.badgersmc.em.domain.stall.StallId`. The Task 1 stub `executeTrade` is replaced by this real body.)

- [ ] **Step 2: Write the failing test**

`ContainerTradeServiceTradeTest.kt` — construct the service with mocked `StallRepository`/`EconomyProvider`/`GuildProvider`/`ShopVaultService` and override the protected `getPlayer`/`getContainer`/`deserializeStack` seams (as the existing `ContainerTradeService` tests do — read one for the pattern). Cover: success deposits to the vault + moves stock; insufficient payment → `Failure`, no deposit; empty chest → `Failure`. Mirror the existing `ContainerTradeService` test's setup helpers.

- [ ] **Step 3: Run RED → implement → GREEN**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew test --tests "net.badgersmc.em.application.ContainerTradeServiceTradeTest" -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: PASS (after implementation; confirm it was RED first).

- [ ] **Step 4: Commit**

```bash
cd /d/BadgersMC-Dev/EnthusiaMarket && git add src/main/kotlin/net/badgersmc/em/application/ContainerTradeService.kt src/test/kotlin/net/badgersmc/em/application/ContainerTradeServiceTradeTest.kt
git commit -m "feat(shop): ContainerTradeService.executeTrade barter path + vault deposit (SP3)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 5: PurchaseMenu TRADE routing + cost lore

**Files:**
- Modify: `src/main/kotlin/net/badgersmc/em/interaction/gui/PurchaseMenu.kt`
- Modify: `src/main/resources/lang/en_US.yml`

- [ ] **Step 1: Route TRADE + label it**

Replace the Task 1 placeholder `TRADE` branch in the result `when` with `tradeService.executeTrade(shop, player.uniqueId)`. Update the button-label selection so TRADE gets its own verb: e.g.
```kotlin
        val buttonKey = when (shop.direction) {
            SignDirection.SELL -> "gui.shop.buy_name"
            SignDirection.TRADE -> "gui.shop.trade_name"
            else -> "gui.shop.sell_action_name"
        }
        val buttonLoreKey = when (shop.direction) {
            SignDirection.SELL -> "gui.shop.buy_lore_click"
            SignDirection.TRADE -> "gui.shop.trade_lore_click"
            else -> "gui.shop.sell_action_lore_click"
        }
```
The cost slot already renders `shop.costItem`; for TRADE that is the real cost item, so it displays correctly. (Optionally pass `"amount" to shop.costAmount` into a `gui.shop.trade_lore_click` line that reads "Pay <amount>×".)

- [ ] **Step 2: Lang keys**

In `en_US.yml` under `gui.shop`, add:
```yaml
    trade_name: "<light_purple><bold>TRADE</bold>"
    trade_lore_click: "<gray>Click to pay <gold><cost></gold>× and receive the item"
```
(If `trade_lore_click` uses `<cost>`, pass `"cost" to shop.costAmount` at the `lang.msg(buttonLoreKey, ...)` call; otherwise omit the token.)

- [ ] **Step 3: Build**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew compileKotlin -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
cd /d/BadgersMC-Dev/EnthusiaMarket && git add src/main/kotlin/net/badgersmc/em/interaction/gui/PurchaseMenu.kt src/main/resources/lang/en_US.yml
git commit -m "feat(shop): PurchaseMenu routes TRADE to executeTrade (SP3)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 6: [TRADE] sign parse + guild rejection + ShopSignRenderer

**Files:**
- Modify: `src/main/kotlin/net/badgersmc/em/application/ShopSignRenderer.kt`
- Modify: `src/main/kotlin/net/badgersmc/em/infrastructure/listeners/SignPlaceListener.kt`
- Modify: `src/main/kotlin/net/badgersmc/em/infrastructure/commands/ShopCommands.kt` (the SP5 `reRenderShopSign` caller)
- Modify: `src/test/kotlin/net/badgersmc/em/application/ShopSignRendererTest.kt`
- Modify: `src/main/resources/lang/en_US.yml`

- [ ] **Step 1: Renderer takes a cost-display string**

Change `ShopSignRenderer.lines` signature from `(..., price: Long)` to `(direction, sellMaterialName, sellAmount, costDisplay: String)`. Body: header color via the `when` from Task 1; line 1 `"${sellAmount}x $sellMaterialName"`; line 2 = `costDisplay`; line 3 `[Shop]`. Update `ShopSignRendererTest` to pass a `costDisplay` string and assert line 2.

- [ ] **Step 2: Update both renderer callers**

- `SignPlaceListener` money path: `signRenderer.lines(direction, held.type.name.lowercase(), amount, "$price")`.
- `ShopCommands.reRenderShopSign` (SP5): build `costDisplay` from the shop — `if (shop.direction == SignDirection.TRADE) "${shop.costAmount}x ${costMat}" else "${shop.costAmount}"` where `costMat = ItemStackSerializer.deserialize(shop.costItem)?.type?.name?.lowercase() ?: "?"`.

- [ ] **Step 3: Parse `[TRADE]` in SignPlaceListener**

Read `SignPlaceListener.kt`. In the `firstLine` `when`, add `"[TRADE]", "TRADE" -> SignDirection.TRADE`. After the stall/auth checks, branch on direction:
- **Guild rejection:** if `direction == SignDirection.TRADE && stall.owner.type == OwnerType.GUILD` → `player.sendMessage(lang.msg("shop.create.no_guild_trade")); event.isCancelled = true; return`.
- **Cost parse:** for TRADE, parse line 3 as `"N material"`:
  ```kotlin
  val costParts = plain.serialize(lines.getOrElse(2) { Component.empty() }).trim().split(" ")
  val costQty = costParts.getOrNull(0)?.toIntOrNull()
  val costMat = costParts.getOrNull(1)?.let { Material.matchMaterial(it) }
  if (costQty == null || costQty <= 0 || costMat == null) {
      player.sendMessage(lang.msg("shop.create.invalid_trade_cost")); event.isCancelled = true; return
  }
  ```
  Then build the `Shop(...)` with `costItem = ItemStackSerializer.serialize(ItemStack(costMat, 1))`, `costAmount = costQty`, `direction = SignDirection.TRADE`. For money directions keep the existing `price` path and the emerald-display `costItem`. (Refactor the existing single `Shop(...)` construction to set `costItem`/`costAmount`/`direction` per branch.)
- **Sign render:** for TRADE pass `costDisplay = "${costQty}x ${costMat.name.lowercase()}"`; for money `"$price"`.

- [ ] **Step 4: Lang keys**

Under `shop.create` add:
```yaml
    no_guild_trade: "<red>Guild-owned stalls can't host [TRADE] shops."
    invalid_trade_cost: "<red>Line 3 must be '<amount> <item>', e.g. '16 diamond'."
```

- [ ] **Step 5: Build + tests**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew test --tests "net.badgersmc.em.application.ShopSignRendererTest" compileKotlin -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: PASS + BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
cd /d/BadgersMC-Dev/EnthusiaMarket && git add -A
git commit -m "feat(shop): [TRADE] sign parse, guild rejection, renderer cost line (SP3)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 7: /shopvault command + GUI + permission

**Files:**
- Modify: `build.gradle.kts`
- Create: `src/main/kotlin/net/badgersmc/em/infrastructure/commands/VaultCommands.kt`
- Create: `src/main/kotlin/net/badgersmc/em/interaction/gui/ShopVaultMenu.kt`
- Modify: `src/main/resources/lang/en_US.yml`

- [ ] **Step 1: Permission node**

In `build.gradle.kts` add `node("enthusiamarket.shop.vault", default = Default.TRUE, description = "Open /shopvault to withdraw barter payments")`.

- [ ] **Step 2: ShopVaultMenu (paginated, mirror SearchResultsMenu)**

`ShopVaultMenu.kt` — `ChestGui(6, ...)`, `StaticPane(9, 6)`; render `vaultService.contents(owner)` 45/page as icons whose displayed amount = total; click → `vaultService.withdraw(owner, item, min(item.maxStackSize, total))` and give to player (`player.inventory.addItem`), leftover → re-deposit + message; shift-click → withdraw `total` capped by inventory space; re-open to refresh. Bottom row prev/next like `SearchResultsMenu`. Constructor `(owner: UUID, vaultService: ShopVaultService, page: Int, lang: LangService)`.

- [ ] **Step 3: VaultCommands**

```kotlin
@Command(name = "shopvault", description = "Withdraw barter payments", aliases = ["svault"])
class VaultCommands(
    private val vaultService: net.badgersmc.em.application.ShopVaultService,
    private val lang: LangService,
) {
    @Subcommand("")            // default / no-arg → open the vault (match how ShopCommands' default verb is declared; if Nexus has no empty-subcommand, use @Subcommand("open") + register a base)
    @Permission("enthusiamarket.shop.vault")
    fun open(@Context sender: CommandSender) {
        val player = sender as? Player ?: run { sender.sendMessage(lang.msg("shop.cmd.players_only")); return }
        net.badgersmc.em.interaction.gui.ShopVaultMenu(player.uniqueId, vaultService, 1, lang).open(player)
    }
}
```
**Read how an existing no-arg/default command opens a menu** (e.g. whether Nexus supports `@Subcommand("")` or needs a different default-handler shape) and match it. If Nexus has no bare-command handler, expose `/shopvault open` and document it in the command description.

- [ ] **Step 4: Lang keys**

Under `gui` add a `vault` block:
```yaml
  vault:
    title: "<dark_gray>Your barter vault"
    amount_lore: "<gray>Stored: <aqua><amount>"
    withdrew: "<green>Withdrew <white><amount>x <item></white>."
    full: "<yellow>Inventory full — <white><left></white> left in the vault."
    empty: "<gray>Your barter vault is empty."
```

- [ ] **Step 5: Build**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew compileKotlin compileTestKotlin -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
cd /d/BadgersMC-Dev/EnthusiaMarket && git add -A
git commit -m "feat(shop): /shopvault command + ShopVaultMenu + permission (SP3)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 8: ShopEditMenu cost-from-hand for TRADE shops

**Files:**
- Modify: `src/main/kotlin/net/badgersmc/em/interaction/gui/ShopEditMenu.kt`
- Modify: `src/main/resources/lang/en_US.yml`

- [ ] **Step 1: TRADE cost section**

Read `ShopEditMenu.kt`. Add an instance var `private var costItemB64: String = shop.costItem`. In `render`, branch the cost column on `shop.direction`:
- **Money (`SELL`/`BUY`)** — keep the existing `+10`/EMERALD/`-10` money controls (unchanged).
- **`TRADE`** — render: a cost-item preview (decoded from `costItemB64`) that, when clicked, sets the cost item from the player's main hand (mirror the sell-item "set from hand" button); plus `+1`/`-1` `costAmount` controls. Update `applyEdits` (and its call) to also carry `costItemB64` and write `costItem = costItemB64` in the `.copy(...)`. Update `ShopEditMenuApplyTest` for the new param.

- [ ] **Step 2: Lang keys**

Under `gui.shop.edit` add:
```yaml
      cost_item: "<gray>Cost item (click: set from hand)"
      cost_amount_up: "<green>+1 cost (<amount>)"
      cost_amount_down: "<red>-1 cost (<amount>)"
```

- [ ] **Step 3: Build + apply test**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew test --tests "net.badgersmc.em.interaction.gui.ShopEditMenuApplyTest" compileKotlin -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: PASS + BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
cd /d/BadgersMC-Dev/EnthusiaMarket && git add -A
git commit -m "feat(shop): ShopEditMenu cost-item-from-hand for TRADE shops (SP3)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 9: Final gate

- [ ] **Step 1: Full verification on the committed HEAD**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew clean detekt test shadowJar -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: BUILD SUCCESSFUL, detekt 0, all tests pass.

- [ ] **Step 2: Mark progress + commit**

Append to `docs/tasks.md`: `- [x] ItemShops parity SP3 — barter / profits vault ([TRADE] shops, item vault, /shopvault)`. Then commit (`docs: mark ItemShops parity SP3 (barter/vault) complete`).

- [ ] **Step 3: Report**

Report the final gate output + commit list. Do NOT push.

---

## Self-Review Notes (for the implementer)

1. **Enum exhaustiveness (Task 1)** — adding `TRADE` breaks `when (direction)` in `PurchaseMenu` and `ShopTradeService` and the `if` in `ShopSignRenderer`. The temporary `executeTrade` stub keeps Task 1 compiling; Task 4 replaces it.
2. **Vault key is Paper NBT (Task 3)** — `Base64(item.clone().apply{amount=1}.serializeAsBytes())`. Always normalize amount to 1 before keying, so quantity lives only in the `amount` column. `ItemStack.deserializeBytes` is static.
3. **executeTrade has no economy (Task 4)** — money never moves; payment items go buyer→vault, stock chest→buyer. Rollback must undo all three legs (stock, payment, vault deposit) on inventory-full.
4. **Guild rejection (Task 6)** — check `stall.owner.type == OwnerType.GUILD` at sign creation; TRADE shops are SOLO-only, so `resolveOwnerUuid` always yields a UUID at trade time.
5. **costItem dual meaning** — money shops: emerald display + money `costAmount`; TRADE shops: real cost item + quantity. `direction` is the discriminator everywhere.
6. **Renderer signature change (Task 6)** — `price: Long` → `costDisplay: String` touches `SignPlaceListener`, `ShopCommands.reRenderShopSign` (SP5), and `ShopSignRendererTest`. Update all three.
7. **No-arg command shape (Task 7)** — verify how Nexus declares a bare `/shopvault` (vs requiring a subcommand) by reading an existing command; don't assume `@Subcommand("")` works.
8. **Constructor churn** — `ContainerTradeService` (+`ShopVaultService`), `VaultCommands`, `ShopVaultMenu` are new/changed deps; fix any direct-construction test. Nexus injects `@Service`/`@Repository` beans automatically.
