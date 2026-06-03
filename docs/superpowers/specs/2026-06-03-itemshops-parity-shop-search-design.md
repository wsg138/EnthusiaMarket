# ItemShops Parity — Sub-project 2: Shop Search

**Date:** 2026-06-03
**Status:** Approved (brainstorming complete)
**Owner:** BadgersMC
**Plugin:** EnthusiaMarket (EM)

## 0. Context

Sub-project 2 of the six-part ItemShops parity programme (build order 1→2→5→6→3→4; SP1 shop
management commands is merged). Guiding rule unchanged: **faithful parity** with ItemShops,
diverging only where EM's architecture forces it.

EM runs the **Vault-money** shop model today; the item-barter + item-vault model is SP3. That
constraint shapes search (below).

## 1. Scope

A player-facing `/shop search` verb that finds shops by item, gated by a per-shop opt-in, with
a paginated results GUI showing live stock.

| Command | Behaviour (mirrors ItemShops) |
|---|---|
| `/shop search <item> [mode] [page]` | Find searchable shops whose **sell item** matches `<item>`. Players → `SearchResultsMenu` GUI; console → paginated chat. |

- **`<item>`** resolves via `Material.matchMaterial(query)` (case-insensitive, accepts e.g. `diamond`, `DIAMOND`, `minecraft:diamond`). Unknown material → "unknown item" message.
- **`mode`** (`sell`/`buy`/`any`, default `any`): parsed and accepted for command-shape parity, but under the money model **only sell matching is active**. `sell` and `any` match the sell item; `buy` returns the "item trading not yet available" message (it lights up in SP3 when shops accept items as cost). This keeps the exact ItemShops command shape so players relearn nothing.
- **`page`** (1-based, default 1).

### Per-shop opt-in (`searchEnabled`)

- New `Shop.searchEnabled: Boolean` (migration **V014**), **default `true`** (discoverable
  marketplace; owners opt out to hide).
- Config key `shop.search.default: Boolean = true` — server-wide default applied to **newly
  created** shops.
- Owner toggles it per shop via a new button in the SP1 `ShopEditMenu` (mirrors ItemShops'
  SEARCH_SLOT). Only `searchEnabled` shops appear in results.

### Results GUI

`SearchResultsMenu` (IFramework, paginated ~45/page): each result icon = the sell item, lore
shows **owner name**, `sellAmt×sell → costAmount`, location, and **trades-available** =
`floor(containerStock / sellAmount)` from a live container read. **Click pastes the shop's
coords into chat** (`<world> <x> <y> <z>`) so the player can navigate — matching ItemShops
(teleport is the admin verb, SP5). Console sender → paginated chat list (10/page) with the same
fields.

### Out of scope (other sub-projects / deferred)

- `buy`/`any`-matches-cost-item — needs barter (SP3).
- Shulker-content matching (ItemShops matches materials inside shulker sell/cost items) —
  advanced, low value; deferred.
- Teleport-to-result — admin verb, SP5.

## 2. Architecture (hexagonal / SPEAR)

- **Domain:** `Shop` gains `searchEnabled: Boolean = true`.
- **Application — `ShopSearchService`** (`@Service`): the pure, testable core.
  - `matches(searchEnabled: Boolean, sellMaterial: Material?, query: Material, mode: SearchMode): Boolean`
    — pure predicate (sell-only today; `BUY` → false until barter).
  - `search(query: Material, mode: SearchMode, shops: List<Shop>, sellMaterialOf: (Shop) -> Material?): List<Shop>`
    — filters all shops by `matches`, preserving order; the `sellMaterialOf` resolver is injected
    so the service is unit-testable without Bukkit (the infra caller supplies a base64→Material
    resolver). `SearchMode` is a small domain enum (`SELL`, `BUY`, `ANY`).
- **Application — config:** add `Shop.search.default` to `EnthusiaMarketConfig` (`shop.search`
  block, `default: Boolean = true`).
- **Infrastructure:**
  - `ShopCommands` gains a `search` subcommand: resolve `Material`, resolve mode, call
    `ShopSearchService.search` (passing a `{ shop -> ItemStackSerializer.deserialize(shop.sellItem)?.type }`
    resolver over `shopRepository.all()`), paginate, then open `SearchResultsMenu` (player) or
    print chat (console).
  - `SearchResultsMenu` (IFramework GUI) — renders the page, reads live container stock for
    trades-available, click → paste coords.
  - `ShopEditMenu` (SP1) — add a search-toggle button writing `searchEnabled`.
  - `ShopFactory.build` — gains a `searchEnabled: Boolean` parameter; the creation paths
    (`CreateShopMenu`, `BedrockCreateShopForm`, `SignPlaceListener`) pass `config.shop.search.default`.
  - `ShopRepositorySql` — persist/read the `search_enabled` column.
- **Persistence — migration V014:** `ALTER TABLE shop_items ADD COLUMN search_enabled INTEGER NOT NULL DEFAULT 1;` (the shop table is `shop_items`)
  (existing rows become searchable — consistent with default-on).

### Data flow

`/shop search diamond` → resolve `Material.DIAMOND` → `ShopSearchService.search(DIAMOND, ANY, allShops, sellMaterialResolver)`
→ filtered list → page slice → `SearchResultsMenu` reads each result's container stock →
trades-available rendered → click pastes coords.

## 3. Permissions & lang

- Reuse `enthusiamarket.shop.use` (already gates the `/shop` verbs from SP1) — ItemShops gates
  search under the same `itemshops.use`. No new node.
- New lang keys under `shop.cmd.search.*` and `gui.shop.search.*`: `usage`, `unknown_item`,
  `buy_unavailable`, `none`, `header`, `line` (console), `no_more`, and GUI `title`, `icon`,
  `result_lore`, plus `clicked_coords`. All `<token>` MiniMessage syntax (never `{token}`).

## 4. Testing

- **`ShopSearchService`** (pure, no Bukkit): `matches` true when sell material == query and
  searchEnabled; false when not searchEnabled; false for `BUY` mode (money model); `SELL`/`ANY`
  match sell. `search` filters a mixed list correctly using a stub `sellMaterialOf` resolver,
  preserves order, respects mode. (`Material` is a plain enum — usable in unit tests without
  MockBukkit.)
- **`Shop` field default**: a minimally-constructed shop has `searchEnabled == true`.
- **Persistence round-trip** (MockBukkit + SQLite, mirroring `StallRepositorySqlKindTest`):
  `search_enabled` survives create→read; an explicit `false` persists.
- **Pagination helper**: a pure `page(list, page, perPage)` returns the right slice + total pages.
- Full gate: `./gradlew detekt test shadowJar` green, detekt 0.

## 5. Decisions locked

| Decision | Choice |
|---|---|
| Search scope (money model) | Sell-only; `mode` arg parsed + accepted, `buy` no-ops until SP3 barter |
| `searchEnabled` default | `true` (default-on) + `shop.search.default` config + edit-menu toggle |
| Results | IFramework GUI with live trades-available + click-to-paste-coords; console → chat |
| Material match | `Material.matchMaterial(query)`; resolve shop sell material via `ItemStackSerializer` |
| Permission | Reuse `enthusiamarket.shop.use` |
| Shulker-content match | Deferred |

## 6. Out of scope (deferred)

- `buy`/cost-item search → SP3 (barter). Shulker-content match → deferred. Teleport-to-result →
  SP5 (admin). Search result sorting by distance/price → future polish.
