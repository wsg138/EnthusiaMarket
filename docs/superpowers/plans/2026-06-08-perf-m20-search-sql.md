# Perf: /shop search → SQL (audit M-20) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Stop `/shop search` from loading the entire `shop_items` table and deserializing every row's NBT on the main thread. Add a denormalized `sell_material` column, query it in SQL, and backfill existing rows once at boot.

**Architecture:** The repository owns `sell_material` — it derives the material name from the shop's `sellItem` on every `upsert` and exposes `findBySellMaterial(material, …)` that filters in SQL (`WHERE sell_material = ? AND search_enabled = 1`). A one-time boot backfill populates the column for rows written before this change (NBT → material can't be done in pure SQL). `/shop search` calls the new method instead of `all()` + in-memory deserialize. No domain or creation-site changes.

**Tech Stack:** Kotlin 2.0.0, JDBC (`ShopRepositorySql`), `MigrationRunner`, JUnit 5 + MockK + SQLite, detekt 1.23.8.

**Reference:** audit finding M-20.

**Standing rules (every task):**
- Prefix bash with `cd <REPO> &&`. On Hermes' box prefix gradle with `export JAVA_HOME=/opt/data/jdk-21.0.11+10 &&`; repo `/opt/data/EnthusiaMarket`, jar `/opt/data/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar`.
- Every gradle command includes `-Plumaguilds.jar=<JAR> --no-daemon --console=plain`.
- LF→CRLF warnings expected. Branch `fix/perf-search-sql` off current `main`. Do not push to BadgersMC (coordinator opens the PR; Hermes pushes to `fork` only).
- TDD. Commit after every task.
- Gate rule: compare each `Run:` to `Expected:`; mismatch → STOP, fix, re-run; HALT after 3 tries.

---

## CONFIRMED API SYMBOLS (verified against the repo)

- **`ShopRepository`** (`domain/shop/ShopRepository.kt`): `upsert(shop: Shop): Shop`, `all(): List<Shop>`, `findById`, etc. **Add `findBySellMaterial(material: String): List<Shop>` and `backfillSellMaterials(): Int`.**
- **`ShopRepositorySql`** (`infrastructure/persistence/ShopRepositorySql.kt`, `@Repository`, ctor `(ds: DataSource)`):
  - `insert`/upsert SQL columns (line ~124): `(stall_id, owner, sign_world, sign_x, sign_y, sign_z, container_world, container_x, container_y, container_z, sell_item, sell_amount, cost_item, cost_amount, trusted, hopper_allow_in, hopper_allow_out, frozen, admin_shop, guild_id, creator_id, direction, search_enabled)` — 23 columns / 23 `?`. The ON CONFLICT update sets the same columns.
  - `private fun bind…`/param-setting sets params 1..23 (and guild_id at 20, search_enabled at 23 per existing code).
  - `mapRow(rs)` reads `sell_item`, `search_enabled`, etc.
  - `queryMany(sql) { bind }` helper exists (used by `findByOwner`); `count(sql){}` helper exists. Mirror them.
- **`ItemStackSerializer.deserialize(base64: String): ItemStack?`** (`application/`) — NBT-safe; `ItemStack.type: org.bukkit.Material`; `Material.name: String` (UPPERCASE, e.g. `DIAMOND_SWORD`).
- **`ShopCommands.search`** (`infrastructure/commands/ShopCommands.kt`): resolves `material = Material.matchMaterial(query)`, builds `mode`, rejects BUY (`buy_unavailable`), then `val results = search.search(material, mode, shopRepository.all()) { ItemStackSerializer.deserialize(it.sellItem)?.type }` → `SearchResultsMenu(results, query, page, lang).open(player)`. `material.name` is the UPPERCASE name to query by.
- **`ShopSearchService`** (`application/`): `matches(searchEnabled, sellMaterial, query, mode)` (pure, keep) + `search(query, mode, shops, sellMaterialOf)` (the in-memory filter — **becomes unused** after this change). Active matching is SELL only (BUY returns `buy_unavailable` at the command).
- **Migrations:** `src/main/resources/migrations/`, latest `V017`. Loaded by `MigrationRunner(ds, resourcePrefix="migrations", …).runAll()` in `EnthusiaMarket.onEnable`. **Next: `V018__shop_sell_material.sql`.**
- **`EnthusiaMarket.onEnable`** runs `MigrationRunner(...).runAll()` early, then registers beans; near the bottom it resolves beans via `ctx.getBean<T>()` (e.g. `ParticleBorderService`). Add the backfill call there.

---

## Task 1: Migration + repository (sell_material write, query, backfill)

**Files:**
- Create: `src/main/resources/migrations/V018__shop_sell_material.sql`
- Modify: `src/main/kotlin/net/badgersmc/em/domain/shop/ShopRepository.kt`
- Modify: `src/main/kotlin/net/badgersmc/em/infrastructure/persistence/ShopRepositorySql.kt`
- Modify: every other `ShopRepository` impl/fake (grep `: ShopRepository`)
- Test: `src/test/kotlin/net/badgersmc/em/infrastructure/persistence/ShopRepositorySqlTest.kt` (extend; mirror its SQLite + `MigrationRunner` harness)

- [ ] **Step 1: Migration**
```sql
-- V018__shop_sell_material.sql — denormalized sell-item material for fast /shop search.
ALTER TABLE shop_items ADD COLUMN sell_material TEXT;
CREATE INDEX IF NOT EXISTS idx_shop_sell_material ON shop_items(sell_material);
```

- [ ] **Step 2: Port methods** — in `ShopRepository`:
```kotlin
    /** Search-enabled shops whose sell item is [material] (UPPERCASE Material name). */
    fun findBySellMaterial(material: String): List<Shop>
    /** Populate sell_material for rows missing it (one-time after V018). Returns rows updated. */
    fun backfillSellMaterials(): Int
```

- [ ] **Step 3: Failing repo tests** (extend `ShopRepositorySqlTest`; it already opens an in-memory SQLite + runs `MigrationRunner`)
```kotlin
    @Test fun `upsert stores sell_material derived from the sell item`() {
        // requires a real ItemStack base64 — use MockBukkit (mirror ShopVaultServiceTest's MockBukkit.mock())
        // OR build the base64 via ItemStackSerializer.serialize(ItemStack(Material.DIAMOND))
        // upsert a shop with that sellItem + searchEnabled = true, then:
        // assertEquals(listOf(theShopId), repo.findBySellMaterial("DIAMOND").map { it.id })
    }
    @Test fun `findBySellMaterial excludes search-disabled shops`() {
        // a shop with sellItem DIAMOND but searchEnabled = false is NOT returned
    }
    @Test fun `backfill fills null sell_material rows`() {
        // insert a row with sell_material left NULL (raw SQL or a pre-V018-style upsert),
        // then repo.backfillSellMaterials() > 0 and findBySellMaterial returns it
    }
```
(If the existing test class isn't set up for MockBukkit, add `@BeforeTest MockBukkit.mock()` / `@AfterTest MockBukkit.unmock()` — the `ItemStack`/serializer round-trip needs it, as in `ShopVaultServiceTest`.)

- [ ] **Step 4: RED** — `./gradlew test --tests "*ShopRepositorySqlTest" -Plumaguilds.jar=… --no-daemon --console=plain` → FAIL.

- [ ] **Step 5: Implement in `ShopRepositorySql`**

(a) A helper to derive the material name:
```kotlin
    private fun sellMaterialOf(shop: Shop): String? =
        net.badgersmc.em.application.ItemStackSerializer.deserialize(shop.sellItem)?.type?.name
```
(b) Add `sell_material` to the INSERT column list + one more `?` to VALUES, and to the ON CONFLICT `DO UPDATE SET` list. Set the new bind param (after `search_enabled`, so param 24) to `sellMaterialOf(shop)` via `ps.setString(24, sellMaterialOf(shop))` (or `setNull(24, Types.VARCHAR)` when null). **Keep the existing 23 params unchanged; append sell_material as the 24th.** Mirror the exact INSERT/UPDATE text already in the file.
(c) Query + backfill:
```kotlin
    override fun findBySellMaterial(material: String): List<Shop> =
        queryMany("SELECT * FROM shop_items WHERE sell_material = ? AND search_enabled = 1") {
            it.setString(1, material)
        }

    override fun backfillSellMaterials(): Int {
        var updated = 0
        val nullRows = queryMany("SELECT * FROM shop_items WHERE sell_material IS NULL") {}
        ds.connection.use { conn ->
            conn.prepareStatement("UPDATE shop_items SET sell_material = ? WHERE id = ?").use { ps ->
                for (shop in nullRows) {
                    val mat = sellMaterialOf(shop) ?: continue
                    ps.setString(1, mat); ps.setLong(2, shop.id); ps.addBatch(); updated++
                }
                ps.executeBatch()
            }
        }
        return updated
    }
```
(`queryMany`'s lambda receives the `PreparedStatement` — match its existing signature; the no-bind form is `queryMany(sql) {}`.)
Add `findBySellMaterial`/`backfillSellMaterials` to any other `ShopRepository` implementor (in-memory fake → filter the list / map-and-return-count).

- [ ] **Step 6: GREEN** — same command → PASS.
- [ ] **Step 7: Commit** — `feat(shop): denormalized sell_material column + findBySellMaterial/backfill (M-20)` + Co-Authored-By trailer.

---

## Task 2: Boot backfill

**Files:** Modify `src/main/kotlin/net/badgersmc/em/EnthusiaMarket.kt`

- [ ] **Step 1: Call the backfill after migrations.** Near the bottom of `onEnable` (next to the other `ctx.getBean<...>()` calls, AFTER the `MigrationRunner(...).runAll()` and bean registration):
```kotlin
        // M-20: one-time backfill of sell_material for shops written before V018.
        val backfilled = ctx.getBean<net.badgersmc.em.domain.shop.ShopRepository>().backfillSellMaterials()
        if (backfilled > 0) logger.info("Backfilled sell_material for $backfilled shop(s)")
```

- [ ] **Step 2: Build** — `./gradlew compileKotlin -Plumaguilds.jar=… --no-daemon --console=plain` → BUILD SUCCESSFUL.
- [ ] **Step 3: Commit** — `feat(shop): backfill sell_material at boot (M-20)` + trailer.

---

## Task 3: /shop search uses the SQL query

**Files:**
- Modify: `src/main/kotlin/net/badgersmc/em/infrastructure/commands/ShopCommands.kt`
- Modify: `src/main/kotlin/net/badgersmc/em/application/ShopSearchService.kt` (remove the now-unused in-memory `search`)
- Test: `src/test/kotlin/net/badgersmc/em/application/ShopSearchServiceTest.kt` (drop the `search` test; keep `matches` tests)

- [ ] **Step 1: Swap the command query.** Replace:
```kotlin
        val results = search.search(material, mode, shopRepository.all()) { shop ->
            ItemStackSerializer.deserialize(shop.sellItem)?.type
        }
```
with:
```kotlin
        // SELL/ANY both match the sell item today (BUY is rejected above). SQL-filtered, no full scan.
        val results = shopRepository.findBySellMaterial(material.name)
```
Leave the BUY rejection, the empty-result message, and the `SearchResultsMenu` open unchanged. If `search`/`ItemStackSerializer` imports become unused in `ShopCommands`, remove them.

- [ ] **Step 2: Remove the dead in-memory search.** Delete `ShopSearchService.search(...)` (the 4-arg in-memory filter); keep `matches(...)` + `SearchMode`. Delete the corresponding `search`-method test in `ShopSearchServiceTest`; keep the `matches` tests.

- [ ] **Step 3: Build + test** — `./gradlew compileKotlin test --tests "*ShopSearchServiceTest" -Plumaguilds.jar=… --no-daemon --console=plain` → BUILD SUCCESSFUL, tests pass. (If `ShopCommandsTest` exercised search via the old path, update it to stub `shopRepository.findBySellMaterial(...)`.)
- [ ] **Step 4: Commit** — `perf(shop): /shop search queries sell_material in SQL, not a full scan (M-20)` + trailer.

---

## Task 4: Final gate

- [ ] **Step 1:** `./gradlew clean detekt test shadowJar -Plumaguilds.jar=… --no-daemon --console=plain` → BUILD SUCCESSFUL, detekt 0, all tests pass, shadowJar built.
- [ ] **Step 2: Report** — gate output + the 4 commit hashes. Do NOT push to BadgersMC; push the branch to `fork` only.

---

## Self-Review Notes (for the implementer)
1. **The repository owns `sell_material`** — derived from `sellItem` on upsert; no `Shop` domain field, no creation-site edits. Search and backfill both live in `ShopRepositorySql`.
2. **Backfill is one-time + idempotent** — it only touches `sell_material IS NULL` rows, so subsequent boots find none. It needs MockBukkit/real serialization in tests because it deserializes NBT.
3. **UPPERCASE material names** — `Material.name` and `findBySellMaterial(material.name)` both use the enum name; the column stores the same.
4. **search_enabled is enforced in SQL** (`AND search_enabled = 1`), preserving the opt-in.
5. **Every `ShopRepository` implementor** must get the two new methods (grep `: ShopRepository`).
6. **Scope** — M-20 only. M-19 (async-prefetch the search-result trades count) is a separate follow-up PR.
