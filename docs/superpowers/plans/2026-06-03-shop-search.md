# Shop Search Implementation Plan (ItemShops Parity SP2)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Add `/shop search <item> [mode] [page]` — find searchable shops by sell item, with a paginated results GUI showing live stock, gated by a per-shop opt-in (default on).

**Architecture:** Hexagonal/SPEAR. A pure `ShopSearchService` (filter logic, unit-tested) + a new `Shop.searchEnabled` column (migration V014). A `@Subcommand` on the existing `ShopCommands` (`/shop`), a `SearchResultsMenu` IFramework GUI (live container stock read), and a search-toggle button added to the SP1 `ShopEditMenu`.

**Tech Stack:** Kotlin 2.0.0, Nexus DI + commands, IFramework GUIs, JDBC migrations, JUnit 5 + MockK + MockBukkit, detekt 1.23.8.

**Reference spec:** `docs/superpowers/specs/2026-06-03-itemshops-parity-shop-search-design.md`

**Standing rules (every task):**
- Prefix bash with `cd <REPO> &&` (`/d/BadgersMC-Dev/EnthusiaMarket`, or `/opt/data/EnthusiaMarket`). On Hermes' box also prefix gradle with `export JAVA_HOME=/opt/data/jdk-21.0.11+10 &&`.
- Every gradle command includes `-Plumaguilds.jar=<JAR> --no-daemon --console=plain` (`/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar` or `/opt/data/...`).
- LF→CRLF git warnings expected. Branch `feat/shop-search`. Do not push (coordinator opens the PR).
- TDD: write failing test, run RED, then GREEN. Commit after every task with the given message.
- Gate rule: compare each `Run:` to `Expected:`; mismatch → STOP, fix, re-run; HALT after 3 tries. Final gate runs on the EXACT committed HEAD.

---

## CONFIRMED API SYMBOLS (verified against the repo — use exactly)

- **Shop table is `shop_items`** (NOT `shops`). Migrations dir: `src/main/resources/migrations/`; next number **V014**.
- **`Shop`** (`net.badgersmc.em.domain.shop.Shop`) — data class; add a field via `.copy(...)`. Existing booleans: `hopperAllowIn`, `hopperAllowOut`, `frozen`, `adminShop`. `init` requires `sellAmount>0`, `costAmount>0`, `stallId` non-blank.
- **`ShopRepository`**: `all(): List<Shop>`, `findById(Long): Shop?`, `upsert(Shop): Shop`, `findByOwner(UUID): List<Shop>`.
- **`ShopRepositorySql`** persists booleans with `ps.setBoolean(n, ...)` / `rs.getBoolean("col")`. Current `bind(...)` sets params 1–22 (last = `direction`); `update(...)` calls `bind` then `ps.setLong(23, shop.id)` for the WHERE. The INSERT has 22 columns / 22 `?`.
- **`ItemStackSerializer`** (`net.badgersmc.em.application`): `deserialize(base64: String): ItemStack?`, `serialize(ItemStack): String`.
- **`org.bukkit.Material.matchMaterial(name: String): Material?`** — resolves a query string to a Material (handles `diamond`, `DIAMOND`, `minecraft:diamond`); null if unknown.
- **Container stock read**: `(block.state as? org.bukkit.block.Container)?.inventory` then `inventory.all(material)` / iterate `inventory.contents` summing `stack.amount` where `stack?.type == material`. The existing trade path uses `containerInv.containsAtLeast(stack, amount)`.
- **Nexus command annotations** (already imported in `ShopCommands.kt`): `@Subcommand`, `@Permission`, `@Context sender: CommandSender`, `@Arg("name") x: Type` (optional via Kotlin default).
- **IFramework GUI** (mirror `interaction/gui/OwnedShopsMenu.kt` from SP1): `ChestGui(rows, ComponentHolder.of(comp))`, `StaticPane(9, rows)`, `GuiItem(itemStack) { event -> event.isCancelled = true; ... }`, `pane.addItem(item, x, y)`, `gui.addPane(pane)`, `gui.show(player)`.
- **`LangService`**: `lang.msg("key", "tok" to v)`; lang placeholders use `<token>` (NEVER `{token}`).
- **`ShopCommands`** (`infrastructure/commands/ShopCommands.kt`, from SP1) — `@Command(name="shop", aliases=["shops"])`; already injects `management`, `shopRepository`, `breakDelete`, `lang`. Add the search subcommand here.
- **`ShopEditMenu`** (`interaction/gui/ShopEditMenu.kt`, SP1) — `render(player)` builds a `ChestGui(3, ...)` + `StaticPane(9,3)`; has instance vars (`sellItemB64`, `sellAmount`, `costAmount`, `hopperIn`, `hopperOut`, `frozen`) and an `applyEdits(...)` companion. The search toggle adds a `searchEnabled` var + a button + extends `applyEdits`.
- **`ShopFactory.build`** (`application/ShopFactory.kt`) — has params ending `..., sellAmount: Int, price: Long, direction: SignDirection`. Add a defaulted `searchEnabled: Boolean = true` param and set `searchEnabled = searchEnabled` in the returned `Shop(...)`.
- **`EnthusiaMarketConfig.Shop`** class — add `var searchDefault: Boolean = true` (the `shop.search.default` key).

---

## Task 1: Shop.searchEnabled field + migration V014 + persistence

**Files:**
- Modify: `src/main/kotlin/net/badgersmc/em/domain/shop/Shop.kt`
- Create: `src/main/resources/migrations/V014__shop_search_enabled.sql`
- Modify: `src/main/kotlin/net/badgersmc/em/infrastructure/persistence/ShopRepositorySql.kt`
- Test: `src/test/kotlin/net/badgersmc/em/infrastructure/persistence/ShopRepositorySqlSearchTest.kt`

- [ ] **Step 1: Add the field to Shop**

In `Shop.kt`, add to the data class constructor (after `frozen`/`adminShop`, before `guildId` is fine — pick a spot among the boolean fields; match the existing default style):

```kotlin
    /** Whether this shop appears in /shop search results (REQ — ItemShops parity SP2). */
    val searchEnabled: Boolean = true,
```

- [ ] **Step 2: Write the migration**

`src/main/resources/migrations/V014__shop_search_enabled.sql`:

```sql
-- ItemShops parity SP2 — per-shop opt-in for /shop search. Default 1 (true):
-- shops are discoverable unless the owner toggles it off in the edit menu.
ALTER TABLE shop_items ADD COLUMN search_enabled INTEGER NOT NULL DEFAULT 1;
```

- [ ] **Step 3: Write the failing persistence round-trip test**

```kotlin
package net.badgersmc.em.infrastructure.persistence

import net.badgersmc.em.domain.shop.Shop
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

class ShopRepositorySqlSearchTest {

    private val dbFile = File.createTempFile("em-shop-search", ".db")
    private lateinit var ds: DataSource

    @BeforeTest fun setup() {
        MockBukkit.mock()
        ds = DatabaseFactory.open(DatabaseSpec.Sqlite(dbFile)).also {
            MigrationRunner(it, resourcePrefix = "migrations", classLoader = javaClass.classLoader).runAll()
        }
    }

    @AfterTest fun teardown() { MockBukkit.unmock(); dbFile.delete() }

    private fun shop(searchEnabled: Boolean) = Shop(
        stallId = "stall1", owner = UUID.randomUUID(),
        signWorld = "world", signX = 1, signY = 2, signZ = 3,
        containerWorld = "world", containerX = 1, containerY = 1, containerZ = 1,
        sellItem = "s", sellAmount = 1, costItem = "c", costAmount = 10,
        direction = SignDirection.SELL, searchEnabled = searchEnabled,
    )

    @Test fun `search_enabled survives a round trip`() {
        val repo = ShopRepositorySql(ds)
        val created = repo.upsert(shop(searchEnabled = false))
        val loaded = repo.findById(created.id)!!
        assertEquals(false, loaded.searchEnabled)
    }

    @Test fun `defaults to true`() {
        val repo = ShopRepositorySql(ds)
        val created = repo.upsert(shop(searchEnabled = true))
        assertEquals(true, repo.findById(created.id)!!.searchEnabled)
    }
}
```

- [ ] **Step 4: Run the test to verify it fails**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew test --tests "net.badgersmc.em.infrastructure.persistence.ShopRepositorySqlSearchTest" -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: FAIL — `searchEnabled` not a constructor param, or column unread.

- [ ] **Step 5: Persist the column in ShopRepositorySql**

Make these four exact edits (the INSERT/UPDATE use positional `?`; `search_enabled` becomes the new last data column, so the UPDATE's WHERE id shifts from param 23 to 24):

(a) **INSERT** — add `search_enabled` to the column list and one more `?`:
```kotlin
                INSERT INTO shop_items
                (stall_id, owner, sign_world, sign_x, sign_y, sign_z,
                 container_world, container_x, container_y, container_z,
                 sell_item, sell_amount, cost_item, cost_amount,
                 trusted, hopper_allow_in, hopper_allow_out, frozen, admin_shop,
                 guild_id, creator_id, direction, search_enabled)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
```

(b) **UPDATE** — add `search_enabled = ?` after `direction = ?`, and renumber the WHERE bind:
```kotlin
                """UPDATE shop_items SET
                     stall_id = ?, owner = ?, sign_world = ?, sign_x = ?, sign_y = ?, sign_z = ?,
                     container_world = ?, container_x = ?, container_y = ?, container_z = ?,
                     sell_item = ?, sell_amount = ?, cost_item = ?, cost_amount = ?,
                     trusted = ?, hopper_allow_in = ?, hopper_allow_out = ?, frozen = ?, admin_shop = ?,
                     guild_id = ?, creator_id = ?, direction = ?, search_enabled = ?
                   WHERE id = ?"""
```
and change `ps.setLong(23, shop.id)` → `ps.setLong(24, shop.id)`.

(c) **bind()** — add as the new param 23 (right after `ps.setString(22, shop.direction.name)`):
```kotlin
        ps.setBoolean(23, shop.searchEnabled)
```

(d) **mapRow** — add to the constructed `Shop(...)` (alongside `frozen = rs.getBoolean("frozen")`):
```kotlin
            searchEnabled = rs.getBoolean("search_enabled"),
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew test --tests "net.badgersmc.em.infrastructure.persistence.ShopRepositorySqlSearchTest" -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: PASS

- [ ] **Step 7: Run the existing shop persistence tests (no regression from the column shift)**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew test --tests "net.badgersmc.em.infrastructure.persistence.*" -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: PASS (all persistence tests green — the positional renumber didn't break ShopRepositorySql round-trips).

- [ ] **Step 8: Commit**

```bash
cd /d/BadgersMC-Dev/EnthusiaMarket && git add src/main/kotlin/net/badgersmc/em/domain/shop/Shop.kt src/main/resources/migrations/V014__shop_search_enabled.sql src/main/kotlin/net/badgersmc/em/infrastructure/persistence/ShopRepositorySql.kt src/test/kotlin/net/badgersmc/em/infrastructure/persistence/ShopRepositorySqlSearchTest.kt
git commit -m "feat(shop): Shop.searchEnabled + V014 migration + persistence (SP2)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 2: ShopSearchService (pure filter)

**Files:**
- Create: `src/main/kotlin/net/badgersmc/em/application/ShopSearchService.kt`
- Test: `src/test/kotlin/net/badgersmc/em/application/ShopSearchServiceTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package net.badgersmc.em.application

import net.badgersmc.em.domain.shop.Shop
import net.badgersmc.em.domain.shop.SignDirection
import org.bukkit.Material
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShopSearchServiceTest {

    private val svc = ShopSearchService()

    private fun shop(id: Long, searchEnabled: Boolean = true) = Shop(
        id = id, stallId = "s", owner = UUID.randomUUID(),
        signWorld = "world", signX = 1, signY = 2, signZ = 3,
        containerWorld = "world", containerX = 1, containerY = 1, containerZ = 1,
        sellItem = "s", sellAmount = 1, costItem = "c", costAmount = 10,
        direction = SignDirection.SELL, searchEnabled = searchEnabled,
    )

    @Test fun `matches sell material when searchEnabled`() {
        assertTrue(svc.matches(true, Material.DIAMOND, Material.DIAMOND, ShopSearchService.SearchMode.SELL))
        assertTrue(svc.matches(true, Material.DIAMOND, Material.DIAMOND, ShopSearchService.SearchMode.ANY))
    }

    @Test fun `does not match a different material`() {
        assertFalse(svc.matches(true, Material.IRON_INGOT, Material.DIAMOND, ShopSearchService.SearchMode.SELL))
    }

    @Test fun `does not match when search disabled`() {
        assertFalse(svc.matches(false, Material.DIAMOND, Material.DIAMOND, ShopSearchService.SearchMode.SELL))
    }

    @Test fun `BUY mode never matches under the money model`() {
        assertFalse(svc.matches(true, Material.DIAMOND, Material.DIAMOND, ShopSearchService.SearchMode.BUY))
    }

    @Test fun `null sell material does not match`() {
        assertFalse(svc.matches(true, null, Material.DIAMOND, ShopSearchService.SearchMode.SELL))
    }

    @Test fun `search filters by sell material and searchEnabled preserving order`() {
        val shops = listOf(shop(1), shop(2, searchEnabled = false), shop(3))
        val resolver: (Shop) -> Material? = { s -> if (s.id == 2L) Material.DIAMOND else if (s.id == 1L) Material.DIAMOND else Material.IRON_INGOT }
        val result = svc.search(Material.DIAMOND, ShopSearchService.SearchMode.SELL, shops, resolver)
        // id 1 = diamond + enabled → in; id 2 = diamond but disabled → out; id 3 = iron → out
        assertEquals(listOf(1L), result.map { it.id })
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew test --tests "net.badgersmc.em.application.ShopSearchServiceTest" -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: FAIL — `ShopSearchService` not defined.

- [ ] **Step 3: Implement ShopSearchService**

```kotlin
package net.badgersmc.em.application

import net.badgersmc.em.domain.shop.Shop
import net.badgersmc.nexus.annotations.Service
import org.bukkit.Material

/**
 * Pure shop-search filter (ItemShops parity SP2). Matches shops by their SELL
 * item material, gated by the per-shop searchEnabled opt-in. Under EM's current
 * Vault-money model only sell matching is active; BUY (search by cost item)
 * needs the barter model (SP3) and always returns false today, so the command
 * keeps the ItemShops mode arg without behaving wrongly.
 */
@Service
class ShopSearchService {

    enum class SearchMode { SELL, BUY, ANY }

    /** True when a shop with [sellMaterial] (searchEnabled=[searchEnabled]) matches [query] under [mode]. */
    fun matches(searchEnabled: Boolean, sellMaterial: Material?, query: Material, mode: SearchMode): Boolean {
        if (!searchEnabled) return false
        val inSell = sellMaterial != null && sellMaterial == query
        return when (mode) {
            SearchMode.SELL -> inSell
            SearchMode.ANY -> inSell // BUY half needs barter (SP3)
            SearchMode.BUY -> false
        }
    }

    /**
     * Filter [shops] to those matching [query]/[mode], using [sellMaterialOf] to
     * resolve each shop's sell-item material (injected so this is unit-testable
     * without Bukkit ItemStack deserialization). Order preserved.
     */
    fun search(
        query: Material,
        mode: SearchMode,
        shops: List<Shop>,
        sellMaterialOf: (Shop) -> Material?,
    ): List<Shop> = shops.filter { matches(it.searchEnabled, sellMaterialOf(it), query, mode) }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew test --tests "net.badgersmc.em.application.ShopSearchServiceTest" -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
cd /d/BadgersMC-Dev/EnthusiaMarket && git add src/main/kotlin/net/badgersmc/em/application/ShopSearchService.kt src/test/kotlin/net/badgersmc/em/application/ShopSearchServiceTest.kt
git commit -m "feat(shop): ShopSearchService pure sell-item filter (SP2)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 3: Config default + wire into shop creation

**Files:**
- Modify: `src/main/kotlin/net/badgersmc/em/config/EnthusiaMarketConfig.kt`
- Modify: `src/main/kotlin/net/badgersmc/em/application/ShopFactory.kt`
- Modify: `src/main/kotlin/net/badgersmc/em/infrastructure/listeners/SignPlaceListener.kt`

- [ ] **Step 1: Add the config key**

In `EnthusiaMarketConfig.kt`, inside `class Shop { ... }`, add:

```kotlin
        @Comment("Whether newly-created shops are searchable by default (/shop search).")
        var searchDefault: Boolean = true
```

- [ ] **Step 2: Add the searchEnabled param to ShopFactory.build**

In `ShopFactory.kt`, add `searchEnabled: Boolean = true` as the last parameter of `build(...)`, and set it in the returned `Shop(...)`:

```kotlin
        direction: SignDirection,
        searchEnabled: Boolean = true,
    ): Shop = Shop(
        // ... existing fields ...
        direction = direction,
        searchEnabled = searchEnabled,
    )
```

- [ ] **Step 3: Pass the config default from the creation paths**

Grep the `ShopFactory.build(` call sites and the direct `Shop(` creation in `SignPlaceListener`:
`cd /d/BadgersMC-Dev/EnthusiaMarket && grep -rn "ShopFactory.build(\|Shop(" src/main/kotlin/net/badgersmc/em/interaction/gui/CreateShopMenu.kt src/main/kotlin/net/badgersmc/em/interaction/bedrock/BedrockCreateShopForm.kt src/main/kotlin/net/badgersmc/em/infrastructure/listeners/SignPlaceListener.kt`

- In `CreateShopMenu` and `BedrockCreateShopForm`: add `searchEnabled = config.shop.searchDefault` to the `ShopFactory.build(...)` call. These classes must have access to `EnthusiaMarketConfig` — if not already injected, add it to the constructor (Nexus injects `@Component`/`@Service` deps; the config bean is available). If injecting config is heavy, instead leave the default (`true`) — but PREFER wiring it so the config key isn't dead.
- In `SignPlaceListener`: the `Shop(...)` is built directly. Add `searchEnabled = config.shop.searchDefault` to that constructor call (SignPlaceListener already has `config`).

If a creation class genuinely cannot reach the config without large churn, fall back to the field default (`true`) for that path and note it in the commit body — but do wire `SignPlaceListener` (the primary live path) for sure.

- [ ] **Step 4: Build**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew compileKotlin -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
cd /d/BadgersMC-Dev/EnthusiaMarket && git add -A
git commit -m "feat(shop): shop.search.default config wired into shop creation (SP2)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 4: ShopEditMenu search toggle

**Files:**
- Modify: `src/main/kotlin/net/badgersmc/em/interaction/gui/ShopEditMenu.kt`

- [ ] **Step 1: Add the searchEnabled toggle**

In `ShopEditMenu.kt`: add an instance var `private var searchEnabled: Boolean = shop.searchEnabled` next to the other vars. In `render(player)`, add a toggle button (pick a free slot, e.g. `7, 1`) mirroring the hopper/freeze buttons:

```kotlin
        pane.addItem(GuiItem(decorated(if (searchEnabled) Material.SPYGLASS else Material.GRAY_DYE, lang.msg("gui.shop.edit.search", "state" to searchEnabled))) {
            it.isCancelled = true; searchEnabled = !searchEnabled; render(player)
        }, 7, 1)
```

Extend the `applyEdits` companion + its call to carry `searchEnabled`:
- Add `searchEnabled: Boolean` as the last param of `applyEdits(...)` and set `searchEnabled = searchEnabled` in the `.copy(...)`.
- Update the save button's call: `shopRepository.upsert(applyEdits(shop, sellItemB64, sellAmount, costAmount, hopperIn, hopperOut, frozen, searchEnabled))`.

Add the lang key in `en_US.yml` under `gui.shop.edit`:
```yaml
      search: "<green>Searchable: <gold><state>"
```

- [ ] **Step 2: Update the ShopEditMenuApplyTest for the new param**

The SP1 test `ShopEditMenuApplyTest` calls `ShopEditMenu.applyEdits(...)`. Add the `searchEnabled` argument to both calls (e.g. `searchEnabled = true`) and assert it carries through in the first test (`assertEquals(true, updated.searchEnabled)`).

- [ ] **Step 3: Run the apply test + build**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew test --tests "net.badgersmc.em.interaction.gui.ShopEditMenuApplyTest" compileKotlin -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: PASS + BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
cd /d/BadgersMC-Dev/EnthusiaMarket && git add src/main/kotlin/net/badgersmc/em/interaction/gui/ShopEditMenu.kt src/test/kotlin/net/badgersmc/em/interaction/gui/ShopEditMenuApplyTest.kt src/main/resources/lang/en_US.yml
git commit -m "feat(shop): searchEnabled toggle in ShopEditMenu (SP2)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 5: `/shop search` + SearchResultsMenu

**Files:**
- Create: `src/main/kotlin/net/badgersmc/em/interaction/gui/SearchResultsMenu.kt`
- Modify: `src/main/kotlin/net/badgersmc/em/infrastructure/commands/ShopCommands.kt`
- Modify: `src/main/resources/lang/en_US.yml`

- [ ] **Step 1: Add lang keys**

In `en_US.yml`, under `shop.cmd` add a `search` block, and under `gui.shop` add a `search` block (use `<token>`):

```yaml
    search:
      usage: "<gray>Usage: /shop search <item> [sell|buy|any] [page]"
      unknown_item: "<red>Unknown item: <query>"
      buy_unavailable: "<yellow>Searching by what a shop buys isn't available yet (item trading coming later)."
      none: "<gray>No searchable shops sell <white><query></white>."
      header: "<gold>Shops selling <white><query></white> (page <page>/<pages>)"
      line: "<gray>- <white><world> <x>,<y>,<z> <dark_gray>| <green><sell_amt>x <gray>for <gold><cost> <dark_gray>| <aqua><trades> in stock <dark_gray>| <yellow><owner>"
```
```yaml
    search:
      title: "<dark_gray>Shops selling <query>"
      result: "<green><sell_amt>x <gray>for <gold><cost>  <dark_gray>| <aqua><trades> trades  <dark_gray>| <yellow><owner>"
      clicked: "<gray>Shop at <white><world> <x> <y> <z> <gray>— pasted to chat."
```

- [ ] **Step 2: Implement SearchResultsMenu**

```kotlin
package net.badgersmc.em.interaction.gui

import com.github.stefvanschie.inventoryframework.adventuresupport.ComponentHolder
import com.github.stefvanschie.inventoryframework.gui.GuiItem
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui
import com.github.stefvanschie.inventoryframework.pane.StaticPane
import net.badgersmc.em.application.ItemStackSerializer
import net.badgersmc.em.domain.shop.Shop
import net.badgersmc.nexus.i18n.LangService
import net.badgersmc.em.interaction.Menu
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.Container
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * Paginated /shop search results (first 45). Each icon = the sell item; lore
 * shows owner, trade, and live trades-available (container stock / sellAmount).
 * Clicking pastes the shop's coords into chat (teleport is the admin verb, SP5).
 */
class SearchResultsMenu(
    private val results: List<Shop>,
    private val query: String,
    private val lang: LangService,
) : Menu {
    override fun open(player: Player) {
        val gui = ChestGui(6, ComponentHolder.of(lang.msg("gui.shop.search.title", "query" to query)))
        val pane = StaticPane(9, 6)
        results.take(45).forEachIndexed { idx, shop ->
            val icon = ItemStackSerializer.deserialize(shop.sellItem) ?: ItemStack(Material.CHEST)
            val meta = icon.itemMeta
            if (meta != null) {
                val owner = Bukkit.getOfflinePlayer(shop.owner).name ?: "Unknown"
                meta.displayName(lang.msg(
                    "gui.shop.search.result",
                    "sell_amt" to shop.sellAmount, "cost" to shop.costAmount,
                    "trades" to tradesAvailable(shop), "owner" to owner,
                ))
                icon.itemMeta = meta
            }
            pane.addItem(GuiItem(icon) {
                it.isCancelled = true
                player.closeInventory()
                player.sendMessage(lang.msg(
                    "gui.shop.search.clicked",
                    "world" to shop.signWorld, "x" to shop.signX, "y" to shop.signY, "z" to shop.signZ,
                ))
            }, idx % 9, idx / 9)
        }
        gui.addPane(pane)
        gui.show(player)
    }

    private fun tradesAvailable(shop: Shop): Int {
        val world = Bukkit.getWorld(shop.containerWorld) ?: return 0
        val state = world.getBlockAt(shop.containerX, shop.containerY, shop.containerZ).state
        val inv = (state as? Container)?.inventory ?: return 0
        val sellStack = ItemStackSerializer.deserialize(shop.sellItem) ?: return 0
        val stock = inv.contents.filterNotNull().filter { it.isSimilar(sellStack) }.sumOf { it.amount }
        return stock / shop.sellAmount.coerceAtLeast(1)
    }
}
```

- [ ] **Step 3: Add the search subcommand to ShopCommands**

Inject `ShopSearchService` into `ShopCommands` (add `private val search: net.badgersmc.em.application.ShopSearchService,` to the constructor). Add:

```kotlin
    @Subcommand("search")
    @Permission("enthusiamarket.shop.use")
    fun search(
        @Context sender: CommandSender,
        @net.badgersmc.nexus.commands.annotations.Arg("query") query: String? = null,
        @net.badgersmc.nexus.commands.annotations.Arg("mode") modeArg: String = "any",
    ) {
        val player = sender as? Player ?: run { sender.sendMessage(lang.msg("shop.cmd.players_only")); return }
        if (query == null) { player.sendMessage(lang.msg("shop.cmd.search.usage")); return }
        val material = org.bukkit.Material.matchMaterial(query)
        if (material == null) { player.sendMessage(lang.msg("shop.cmd.search.unknown_item", "query" to query)); return }
        val mode = when (modeArg.lowercase()) {
            "sell" -> net.badgersmc.em.application.ShopSearchService.SearchMode.SELL
            "buy" -> net.badgersmc.em.application.ShopSearchService.SearchMode.BUY
            else -> net.badgersmc.em.application.ShopSearchService.SearchMode.ANY
        }
        if (mode == net.badgersmc.em.application.ShopSearchService.SearchMode.BUY) {
            player.sendMessage(lang.msg("shop.cmd.search.buy_unavailable")); return
        }
        val results = search.search(material, mode, shopRepository.all()) { shop ->
            net.badgersmc.em.application.ItemStackSerializer.deserialize(shop.sellItem)?.type
        }
        if (results.isEmpty()) { player.sendMessage(lang.msg("shop.cmd.search.none", "query" to query)); return }
        net.badgersmc.em.interaction.gui.SearchResultsMenu(results, query, lang).open(player)
    }
```

- [ ] **Step 4: Build + fix ShopCommands test constructor if present**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew compileKotlin compileTestKotlin -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: BUILD SUCCESSFUL. If a `ShopCommands` test constructs it directly, add a `mockk<ShopSearchService>(relaxed = true)` arg.

- [ ] **Step 5: Commit**

```bash
cd /d/BadgersMC-Dev/EnthusiaMarket && git add src/main/kotlin/net/badgersmc/em/interaction/gui/SearchResultsMenu.kt src/main/kotlin/net/badgersmc/em/infrastructure/commands/ShopCommands.kt src/main/resources/lang/en_US.yml
git commit -m "feat(shop): /shop search + SearchResultsMenu with live trades-available (SP2)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 6: Final gate

- [ ] **Step 1: Full verification on the committed HEAD**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew clean detekt test shadowJar -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: BUILD SUCCESSFUL, detekt 0, all tests pass.

- [ ] **Step 2: Mark progress + commit**

Append to `docs/tasks.md`: `- [x] ItemShops parity SP2 — /shop search (searchEnabled opt-in, results GUI with live stock)`. Then:

```bash
cd /d/BadgersMC-Dev/EnthusiaMarket && git add docs/tasks.md
git commit -m "docs: mark ItemShops parity SP2 (shop search) complete

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

- [ ] **Step 3: Report**

Report the final gate output + commit list. Do NOT push.

---

## Self-Review Notes (for the implementer)

1. **`Shop` field insertion (Task 1)** — `Shop` has many params; add `searchEnabled: Boolean = true` anywhere with a default so existing constructions still compile. The persistence round-trip test is the real check.
2. **Positional JDBC (Task 1 Step 5)** — the four edits must stay consistent: INSERT gets 23 columns/`?`, bind sets param 23, UPDATE adds `search_enabled = ?` and the WHERE id moves to `ps.setLong(24, ...)`. Run the existing persistence tests (Step 7) to confirm nothing shifted wrong.
3. **`EnthusiaMarketConfig.shop` access path (Task 3)** — confirm it's `config.shop.searchDefault` (the block is `class Shop { var searchDefault }`, accessed as `config.shop.searchDefault`).
4. **`SignPlaceListener` `Shop(` constructor (Task 3)** — read it; it builds `Shop(...)` directly with named args. Add `searchEnabled = config.shop.searchDefault`.
5. **`ShopEditMenu.applyEdits` callers (Task 4)** — the only callers are the menu's own save button + `ShopEditMenuApplyTest`. Update both for the new param.
6. **`decorated(...)` helper (Task 4)** — exists in `ShopEditMenu` already (SP1); reuse it for the search button.
