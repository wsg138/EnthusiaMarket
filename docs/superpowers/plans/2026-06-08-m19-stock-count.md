# M-19 — Denormalize container stock into `shop_items.stock_count`

## Goal

`/shop search` results render a live "trades available" number per shop. Today
`SearchResultsMenu.tradesAvailable()` reads the container block state
(`world.getBlockAt(...).state` → `Container.inventory`) for **up to 45 shops per
page**, on the main thread, during GUI paint. `getBlockAt().state` **force-loads
the chunk** if it is not resident — so opening a search page can trigger dozens
of synchronous chunk loads = a tick stall at scale.

Fix: denormalize the container stock into a `stock_count` column on `shop_items`,
kept fresh by the existing infrastructure listeners, and have `SearchResultsMenu`
read that column instead of touching block state. After this change the search
GUI does **zero** Bukkit block access.

## Design (all changes stay in infrastructure + a single additive domain field)

- Store **raw stock** = total count of sell-item-matching items in the container
  (NOT trades). Trades are derived cheaply at read time as
  `stockCount / sellAmount.coerceAtLeast(1)`. Storing raw stock means a later
  `sellAmount` edit does not make the cached value wrong.
- `ContainerStockListener` already recomputes per-shop stock and refreshes the
  sign on every manual container edit (`InventoryClickEvent` / `InventoryDragEvent`,
  MONITOR priority). It is the natural place to also persist the count.
- Sign-click trades mutate `container.inventory` directly and do **not** fire
  `InventoryClickEvent`, so the listener won't catch them. They DO fire
  `PostShopTransactionEvent(shopId=…)` after every trade — react to that to
  recompute. This keeps the application-layer `ContainerTradeService` untouched.

### Known limitation (acceptable — document, do not solve)

Hopper-only stock changes and shops whose container chunk is unloaded will show a
slightly stale `stock_count` until the next manual edit / trade / server boot.
This **matches the current sign-stock behavior** (the sign is already not updated
on hopper-only moves), so it is not a regression. The search number is a hint;
clicking a result still teleports/echoes coords regardless of the cached count.

## CONFIRMED API SYMBOLS (verified against current `main` — do not re-derive)

- `data class Shop(... , val searchEnabled: Boolean = true)` in
  `domain/shop/Shop.kt`. Has an `init {}` block with `require(...)` guards. Add the
  new field with a default so legacy rows and all existing constructor call-sites
  keep compiling.
- `interface ShopRepository` in `domain/shop/ShopRepository.kt` — already has
  `findBySellMaterial`, `backfillSellMaterials`. Annotated `@Suppress("TooManyFunctions")`.
- `ShopRepositorySql` in `infrastructure/persistence/ShopRepositorySql.kt` —
  `insert()`, `update()`, `bind()`, `mapRow()`. **NOTE:** `sell_material` (V018)
  was merged to main; the column list / param indices already include it. Read the
  current file and add `stock_count` following the SAME pattern as the existing
  columns. Let the round-trip test enforce correct param wiring — do not hardcode
  indices from memory.
- Migrations dir `src/main/resources/migrations/`; latest is `V018__shop_sell_material.sql`.
  New file: `V019__shop_stock_count.sql`.
- `ContainerStockListener` in `infrastructure/listeners/` — `@Component`, `Listener`,
  `@PostConstruct fun register()`. Has `refreshShopsAt(containerBlock: Block)`,
  `computeTradesAvailable(shop, container: Container): Int`,
  `computeTradesForShop(shop): Int`. Constructor:
  `(private val shopRepository: ShopRepository, private val lang: LangService)`.
- `PostShopTransactionEvent(buyer, landlordId, item, quantity, pricePaid, shopId: Long = 0, direction)`
  in `events/` — Bukkit `Event` with companion `handlerList`.
- `SearchResultsMenu` in `interaction/gui/` — `tradesAvailable(shop): Int` at the
  bottom reads block state. `open(player)` calls it while building each icon's lore.
- `ShopRepositorySqlTest` uses MockBukkit + in-memory SQLite + `MigrationRunner`.
- Gradle gate flags: `export JAVA_HOME=/opt/data/jdk-21.0.11+10` then
  `-Plumaguilds.jar=/opt/data/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`.

## Tasks (TDD — failing test first where noted)

### T1 — Migration `V019__shop_stock_count.sql`
Create `src/main/resources/migrations/V019__shop_stock_count.sql`:
```sql
-- V019__shop_stock_count.sql — denormalized container stock for async /shop search rendering.
ALTER TABLE shop_items ADD COLUMN stock_count INTEGER NOT NULL DEFAULT 0;
```
No index needed (read by id, not filtered on).
Commit: `feat(shop): V019 stock_count column for denormalized search stock (M-19)`

### T2 — Domain field `Shop.stockCount`  (TDD)
RED: add a test in the appropriate domain test (or extend ShopRepositorySqlTest in
T3) asserting a `Shop` carries `stockCount` defaulting to 0.
GREEN: in `domain/shop/Shop.kt` add `val stockCount: Int = 0` (place it after
`searchEnabled`, before the closing `)`/`init`). Default keeps every existing
call-site + legacy DB row valid. Do NOT add a `require` for it (0 is valid).
Commit: `feat(shop): add Shop.stockCount domain field (M-19)`

### T3 — Persist + read `stock_count` in `ShopRepositorySql`  (TDD)
RED: extend `ShopRepositorySqlTest`:
1. `upsert` a shop with `stockCount = 7`; `findById` → `stockCount == 7`.
2. New `updateStock(id, 3)` → `findById` → `stockCount == 3`, and assert no other
   field changed (e.g. `sellAmount`, `owner` unchanged).
GREEN:
- Add to `ShopRepository`: `/** Set the denormalized container stock for [id]. */ fun updateStock(id: Long, stockCount: Int)`.
- In `ShopRepositorySql`: add `stock_count` to the INSERT column list + value
  placeholder + `bind`/insert wiring; add to the UPDATE set-list; read it in
  `mapRow` (`stockCount = rs.getInt("stock_count")`). Implement `updateStock` as a
  targeted `UPDATE shop_items SET stock_count = ? WHERE id = ?`.
- Keep CCN/NLOC within Codacy limits (≤5 / ≤20). `updateStock` is trivial; mirror
  the existing `setGuildOwnership` connection/prepare/use shape.
Commit: `feat(shop): persist + read stock_count in ShopRepositorySql (M-19)`

### T4 — `ContainerStockListener` persists stock + reacts to trades
- Refactor `computeTradesAvailable` (or add a sibling) so the listener has the
  **raw stock** (sum of matching `item.amount`) available, not only the trade
  count. Persist raw stock: in `refreshShopsAt`, after resolving each shop's
  container, call `shopRepository.updateStock(shop.id, rawStock)`.
  - Sign line stays as trades: `trades = rawStock / shop.sellAmount.coerceAtLeast(1)`.
- Add a new handler so sign-click trades (which don't fire InventoryClickEvent)
  also refresh stock + sign:
  ```kotlin
  @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
  fun onTransaction(event: net.badgersmc.em.events.PostShopTransactionEvent) {
      val shop = shopRepository.findById(event.shopId) ?: return
      val signWorld = Bukkit.getWorld(shop.signWorld) ?: return
      val signBlock = signWorld.getBlockAt(shop.signX, shop.signY, shop.signZ)
      val state = signBlock.state as? Sign ?: return
      val trades = computeTradesForShop(shop)          // also persists via the shared helper
      updateSignStock(state, trades)
      trackDepletion(shop, trades)
  }
  ```
  Ensure the stock persistence happens on this path too — easiest is to fold the
  `updateStock` call into a single shared helper both `refreshShopsAt` and
  `onTransaction` use (e.g. `recomputeAndPersist(shop): Int` returning trades).
  Watch Codacy CCN: extract helpers rather than nesting.
- TDD here is awkward (Bukkit listener + block state). A focused unit test is
  optional; if MockBukkit makes it feasible, add one asserting `updateStock` is
  called with the right raw count after a simulated edit. Otherwise rely on the
  T3 repo test + the gate, and keep this task's logic minimal/obvious.
Commit: `feat(shop): persist container stock on edit + trade (M-19)`

### T5 — `SearchResultsMenu` reads `stock_count` (no block access)  (the perf win)
Replace the body of `tradesAvailable(shop)` with:
```kotlin
private fun tradesAvailable(shop: Shop): Int =
    shop.stockCount / shop.sellAmount.coerceAtLeast(1)
```
Remove now-unused imports (`org.bukkit.block.Container`, and `Bukkit.getWorld`/
`getBlockAt` usage in that method — keep `Bukkit` if still used elsewhere in the
file, e.g. `Bukkit.getOfflinePlayer`, `Bukkit.getWorld` in the click handler).
Run the compiler to find dead imports; do not delete imports still referenced.
Commit: `perf(shop): SearchResultsMenu reads denormalized stock_count (M-19)`

### T6 — Best-effort backfill on enable (loaded chunks only)
In `EnthusiaMarket.onEnable`, after listeners are registered, schedule a one-shot
**main-thread** task that iterates all shops and, only when the container's chunk
is already loaded (`world.isChunkLoaded(chunkX, chunkZ)` — never force-load),
computes raw stock and calls `updateStock`. Skip unloaded chunks (they populate on
first interaction). Keep it cheap and fail-open (wrap in try/catch, log a warning
on failure). This must run on the main thread (block state access) — do NOT use
`runTaskAsynchronously` here.
- Reuse the same raw-stock computation as T4 (consider a small reusable function
  to avoid duplication, but do not over-engineer).
Commit: `feat(shop): best-effort stock_count backfill on enable (M-19)`

## Final gate (must pass before pushing)
```
export JAVA_HOME=/opt/data/jdk-21.0.11+10
./gradlew clean detekt test shadowJar \
  -Plumaguilds.jar=/opt/data/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar \
  --no-daemon --console=plain
```
Must end `BUILD SUCCESSFUL`, detekt 0, all tests green. Then push the branch to the
`fork` remote ONLY.

## Files touched (do not touch anything else)
- `src/main/resources/migrations/V019__shop_stock_count.sql` (new)
- `src/main/kotlin/net/badgersmc/em/domain/shop/Shop.kt`
- `src/main/kotlin/net/badgersmc/em/domain/shop/ShopRepository.kt`
- `src/main/kotlin/net/badgersmc/em/infrastructure/persistence/ShopRepositorySql.kt`
- `src/main/kotlin/net/badgersmc/em/infrastructure/listeners/ContainerStockListener.kt`
- `src/main/kotlin/net/badgersmc/em/interaction/gui/SearchResultsMenu.kt`
- `src/main/kotlin/net/badgersmc/em/EnthusiaMarket.kt`
- `src/test/kotlin/net/badgersmc/em/infrastructure/persistence/ShopRepositorySqlTest.kt`
