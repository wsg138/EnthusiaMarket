# ItemShops Parity — Sub-project 1: Shop Management Commands

**Date:** 2026-06-03
**Status:** Approved (brainstorming complete)
**Owner:** BadgersMC
**Plugin:** EnthusiaMarket (EM)

## 0. Context — the ItemShops parity programme

EM deprecates **ItemShops** (p2wn / dev.enthusia.itemshops). Players must keep the exact
verbs and UX they know, so the guiding rule for the whole programme is **faithful parity**:
where ItemShops has a behaviour, EM mirrors it; we only diverge where EM's architecture
forces it.

The full parity effort is decomposed into **six sub-projects**, each its own spec → plan →
PR (small, reviewable):

1. **Shop management commands** ← *this spec*
2. Shop search
3. Profits vault (item barter + item vault) — the "item trading later" model
4. Limits + market regions
5. Admin tooling
6. Misc/integration

Build order: **1 → 2 → 5 → 6 → 3 → 4** (front-load low-risk player value; defer the two
architectural forks — barter/vault and market-regions — to last).

### Architectural reframing (confirmed)

ItemShops is **item-for-item barter with an item vault**: the buyer pays
`costAmount × costItem`, receives `sellAmount × sellItem` from the chest, and the cost items
land in the owner's **item vault** (`/shopvault`, withdrawn as physical items). EM today is
**Vault-money** shops (`costAmount` = a money price). Porting ItemShops' barter + item-vault
model is **sub-project 3** ("item trading comes later"). Sub-projects 1/2/5/6 operate on EM's
**current Vault-money model** and must be designed so they do not block the later barter
addition.

## 1. Scope of this sub-project

A new top-level `/shop` command (aliases `shops`) exposing the **player-facing** management
verbs, matching ItemShops:

| Verb | Behaviour (mirrors ItemShops) |
|---|---|
| `/shop list` | Chat list of the sender's shops: world, coords, `sellAmt×sellItem → costAmount`. |
| `/shop edit` | Opens `OwnedShopsMenu` (paginated GUI of the sender's shops); clicking a shop opens the restructured `ShopEditMenu`. |
| `/shop trust <player> [all\|menu]` | `all` → trust the player on every owned shop; default (`menu`) opens `BulkTrustMenu` to select shops. |
| `/shop untrust <player> [all]` | Mirror of trust. |
| `/shop delete [all]` | Opens `DeleteShopsMenu` over the sender's shops; `all` requires admin and targets every shop. |
| `/shop breakdelete [on\|off\|Nm]` | Per-player **timed mode** (default 5 minutes): while active, breaking your own shop sign deletes the shop instead of being blocked. |

**Permission nodes** (added to the nexus-permissions DSL, generated into `paper-plugin.yml`):
`enthusiamarket.shop.use` (default true) gates the player verbs; `enthusiamarket.shop.delete.all`
(default op) gates `delete all`.

### Out of scope (other sub-projects)

- **Admin verbs** — `adminview`, `info` (admin overlay), `breakothers`, `remove`, `teleport`,
  `fix`, `reload` are admin-only in ItemShops → **sub-project 5**.
- **`search`** → sub-project 2. **`vault`/barter** → sub-project 3.
- **Editing the cost as an *item*** (barter) → sub-project 3. Here the edit menu's cost is a
  **money amount**.

## 2. Architecture (hexagonal / SPEAR)

Layer rule unchanged: domain ← application ← infrastructure.

### New / changed components

- **Application — `ShopManagementService`** (`@Service`): the testable core.
  - `shopsOwnedBy(owner: UUID): List<Shop>` — delegates `ShopRepository.findByOwner`.
  - `trust(owner: UUID, target: UUID, shopIds: List<Long>): Int` — add `target` to each shop's
    `trusted`, persist via `upsert`, return count changed. `trustAll(owner, target)` convenience.
  - `untrust(...)` / `untrustAll(...)` — mirror.
  - `delete(owner: UUID, shopId: Long): Boolean` — ownership-checked delete via `ShopRepository.delete`.
  - `deleteAll(owner: UUID): Int` — delete every shop the owner owns.
  - Pure domain logic (ownership checks, trusted-set mutation) is unit-tested with mockk.
- **Application — `BreakDeleteMode`** (`@Component`): in-memory per-player timed toggle.
  - `enable(player: UUID, durationMs: Long)`, `disable(player: UUID)`, `isActive(player: UUID, nowMs: Long): Boolean`.
  - `ConcurrentHashMap<UUID, Long>` of expiry timestamps; `isActive` checks + purges expired.
  - No persistence (mode is ephemeral, exactly like ItemShops `enableBreakDelete`).
- **Infrastructure — `ShopCommands`** (`@Command("shop", aliases = ["shops"])`): thin command
  layer; each subcommand validates the sender, resolves args, and calls the service / opens a menu.
- **Infrastructure GUIs** (implement EM's existing `net.badgersmc.em.interaction.Menu`,
  IFramework `ChestGui`, mirroring `PurchaseMenu`/`CreateShopMenu`):
  - `OwnedShopsMenu(shops, mgr, lang)` — paginated icons of the owner's shops (icon = sell item,
    lore = trade + coords). Click → `ShopEditMenu`.
  - `BulkTrustMenu(owner, target, mgr, lang)` — toggle which shops the target is trusted on; a
    confirm applies via `ShopManagementService.trust`.
  - `DeleteShopsMenu(shops, mgr, lang, all)` — pick shops to delete; confirm calls `delete`.
- **Infrastructure — restructured `ShopEditMenu`** (see §3).
- **Infrastructure — `BlockProtectionListener`** (existing): consult `BreakDeleteMode` so that,
  when active for the owner, breaking the owner's own shop sign is allowed and triggers shop
  deletion (instead of the current block-protection deny). Trusted-player break behaviour is
  unchanged.

### Data / persistence

- **No migration.** `Shop.trusted` is already persisted (comma-joined UUIDs in the `trusted`
  column); `delete`, `findByOwner`, `upsert` all already exist on `ShopRepository`.
- `BreakDeleteMode` is in-memory only.

## 3. `ShopEditMenu` restructure

Current EM `ShopEditMenu(shop, shopRepository, lang)` edits freeze / hopper / trust. Restructure
to ItemShops' layout (parity), and move trust out to the `/shop trust` verb:

**New edit menu layout** (single `ChestGui`):
- **Sell item slot** — the owner places an `ItemStack`; on save, `sellItem = base64(stack)` and
  `sellAmount = stack.amount` (mirrors EM's create flow + ItemShops' SELL_SLOT).
- **Sell amount controls** — `+/-` buttons adjusting `sellAmount` (1..stack max), for when the
  owner wants an amount different from the placed stack size.
- **Cost (money) controls** — `+/-` buttons adjusting `costAmount` (≥ 1). *(This is the money
  price under EM's current model. Sub-project 3 adds an item-cost mode here for barter.)*
- **Hopper-in toggle** / **Hopper-out toggle** — flip `hopperAllowIn` / `hopperAllowOut`.
- **Freeze toggle** — flip `frozen` (kept from the current EM edit menu; removing it would be a
  functional regression and ItemShops also lets owners pause a shop). The standalone
  `/shop freeze` admin verb is sub-project 5; the owner-facing toggle stays here.
- **Delete button** — delete this shop (same path as `/shop delete`).
- **Save** — `shopRepository.upsert` with the edited copy.

Trust is **removed** from this menu (now `/shop trust`).

Constructor gains the `ShopManagementService` (for delete) and keeps `shopRepository`, `lang`.

## 4. `breakdelete` flow

1. `/shop breakdelete [on|off|Nm]` — parses ItemShops-style: `off` disables; `on`/absent →
   5 minutes; `Nm` (e.g. `10m`) → N minutes. Calls `BreakDeleteMode.enable/disable`.
2. While active, `BlockProtectionListener` on a sign-break by the **owner**: if the broken block
   is one of the owner's shop signs and `BreakDeleteMode.isActive(owner)`, allow the break and
   call `ShopManagementService.delete(owner, shop.id)`; send a confirmation message.
3. Mode auto-expires; `isActive` purges on read. No persistence across restart (parity).

## 5. Error handling

- All verbs are players-only except where noted; non-player sender → translated `command.players_only`.
- Unknown target player on trust/untrust → `shop.trust.unknown_player`.
- `delete all` without `enthusiamarket.shop.delete.all` → `command.no_permission`.
- Empty shop list (list/edit/delete/trust menus) → `shop.none_owned` message, no menu opened.
- Ownership checks in `ShopManagementService` are authoritative; menus are a convenience layer.

## 6. Testing

- **`ShopManagementService`** (pure, mockk `ShopRepository`): trust adds to the set + persists
  + returns count; untrust removes; delete enforces ownership (rejects non-owner); deleteAll
  counts; trustAll/untrustAll iterate all owned shops.
- **`BreakDeleteMode`**: enable→isActive true within window; expiry→isActive false + purged;
  disable→inactive; per-player isolation.
- **`ShopEditMenu`** edit logic: extract a pure helper `applyEdits(shop, sellItemB64, sellAmount,
  costAmount, hopperIn, hopperOut, frozen): Shop` and assert the resulting `Shop` copy; menu
  wiring itself is thin.
- **Command arg parsing** for `breakdelete` (`off`/`on`/`5m`/`10m`/garbage→default) via a pure
  `parseBreakDeleteDuration(arg): Long?` helper.
- Full gate: `./gradlew detekt test shadowJar` green, detekt 0.

## 7. Permissions & lang

- DSL nodes: `enthusiamarket.shop.use` (default true, child of `enthusiamarket.player`),
  `enthusiamarket.shop.delete.all` (default op, child of `enthusiamarket.admin`).
- New lang keys under `shop.*` and `gui.shop.*`: `shop.list.header`/`shop.list.line`,
  `shop.none_owned`, `shop.trust.*`, `shop.untrust.*`, `shop.delete.*`, `shop.breakdelete.*`,
  `gui.shop.owned.title`, `gui.shop.trust.title`, `gui.shop.delete.title`, and edit-menu keys.
  All use `<token>` MiniMessage placeholder syntax (EM convention — never `{token}`).

## 8. Decisions locked

| Decision | Choice |
|---|---|
| Command surface | Dedicated `/shop` (aliases `shops`), separate from `/em` |
| Targeting model | Menu/list-driven (no look-at), matching ItemShops exactly |
| Trade model for 1/2/5/6 | EM's current Vault-money; barter+item-vault deferred to sub-project 3 |
| Edit menu | Restructured to ItemShops layout (sell/amount/cost/hopper/delete); trust moved to `/shop trust` |
| `breakdelete` | Per-player in-memory timed mode (default 5m), consulted by BlockProtectionListener |
| Persistence | None new — `trusted` already persisted; mode is ephemeral |

## 9. Out of scope (deferred)

- Admin verbs (`adminview`/`info`/`breakothers`/`remove`/`teleport`/`fix`/`reload`) → sub-project 5.
- `search` → sub-project 2. Item-cost/barter editing + `/shopvault` → sub-project 3.
- `maxshops` cap + `/shopmarket` regions → sub-project 4.
- `/shophelp`, `/store`, advancement shop events → sub-project 6.
