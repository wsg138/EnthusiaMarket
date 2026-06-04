# ItemShops Parity — Sub-project 5: Admin Tooling

**Date:** 2026-06-04
**Status:** Approved (brainstorming complete)
**Owner:** BadgersMC
**Plugin:** EnthusiaMarket (EM)

## 0. Context — the ItemShops parity programme

EM deprecates **ItemShops** (p2wn / dev.enthusia.itemshops). The guiding rule is **faithful
parity**: where ItemShops has a behaviour, EM mirrors it; we diverge only where EM's architecture
forces it. The effort is six sub-projects, build order **1 → 2 → 5 → 6 → 3 → 4**:

1. Shop management commands — **done** (PR #26)
2. Shop search — **done** (PR #28)
3. Profits vault (item barter + item vault) — later
4. Limits + market regions — later
5. **Admin tooling** ← *this spec*
6. Misc/integration

This sub-project ports the ItemShops **admin shop-verbs** that SP1 deferred. It operates on EM's
current **Vault-money** shop model and must not block the later barter addition (SP3).

## 1. Scope of this sub-project

The ItemShops admin verbs deferred from SP1 were: `adminview`, `info` (admin overlay),
`breakothers`, `remove`, `teleport`, `fix`, `reload`. `reload` is dropped — EM already has
`/em reload`. The remaining six land as an **admin subtree on the existing `/shop` command**,
permission-hidden from non-admins by Brigadier (Nexus already gates `@Subcommand`s this way).

| Verb | Behaviour |
|---|---|
| `/shop admin view` | Raycast the sign/container in the crosshair → open the SP1 `ShopEditMenu` on that shop, bypassing the owner check. |
| `/shop admin info` | Raycast → chat info card: owner name, sign coords, container coords, direction, `sellAmt×item → costAmount`, trusted count, frozen, searchEnabled. |
| `/shop admin remove` | Raycast → delete that shop regardless of owner; fire `ShopDeletedEvent`. |
| `/shop admin fix` | Raycast → re-render the four sign lines from stored shop data **and** verify the linked container still exists; if it's gone, report it and suggest `remove`. |
| `/shop admin breakothers [on\|off\|Nm]` | Timed admin toggle (default 5 min, mirrors SP1 `breakdelete`). While active, the admin breaking **any** shop sign deletes that shop instead of being blocked. |
| *teleport* | **Not a command.** In the SP2 `SearchResultsMenu`, an admin (holds `enthusiamarket.admin.shop`) clicking a result teleports to the shop sign; non-admins keep the existing coords-to-chat. This is the deferred "teleport is the admin verb, SP5" hook from the SP2 spec. |

### Targeting

`view`, `info`, `remove`, and `fix` act on **the shop the admin is looking at** — a single
raycast (`Player.getTargetBlockExact(6)`) resolved to a shop via `findBySign` (sign block) or
`findByContainer` (container block, first match). No coordinate arguments. `breakothers` is a
stateful toggle with no target. teleport's target is the clicked search result.

### Permissions

One node: **`enthusiamarket.admin.shop`** (default `op`). It gates all five `/shop admin`
subcommands and the admin search-teleport branch. Added to the nexus-permissions DSL in
`build.gradle.kts`, generated into `paper-plugin.yml`.

## 2. Architecture

Hexagonal/SPEAR. Pure, unit-testable units; Bukkit-touching glue kept thin.

### New units

- **`AdminBreakMode`** (`application/`) — a byte-for-byte sibling of `BreakDeleteMode`: a
  per-UUID timed toggle (`enable`/`disable`/`isActive`, `parseDurationMs` reused from
  `BreakDeleteMode.parseDurationMs`). Not persisted; clears on restart. `@Component`.
- **`LookAtShopResolver`** (`application/`) — resolves the shop an actor is looking at. Pure core
  takes the target block's `resolve(world: String?, x, y, z)` and returns a `Shop?` via the injected
  `ShopRepository` (`findBySign` first, then `findByContainer().firstOrNull()`); the Bukkit raycast
  (`getTargetBlockExact`) and coord extraction are done by the caller, so the resolution logic is
  testable without a live world. `@Service`.
- **`ShopSignRenderer`** (`application/`) — pure: `lines(shop: Shop): List<Component>` produces the
  four formatted sign lines (`[SELL]`/`[BUY]`, `Nx item`, `price`, `[Shop]`). Extracted from the
  inline block in `SignPlaceListener` so **both** shop creation and `fix` render identically. No
  Bukkit world access; the material name comes from `ItemStackSerializer.deserialize(shop.sellItem)?.type`.

### Changed units

- **`ShopManagementService.adminDelete(shopId: Long): Boolean`** — owner-bypassing delete (mirrors
  the existing `delete` but without the `shop.owner != actor` guard); fires `ShopDeletedEvent` via
  the existing null-safe `fireShopDeleted` path.
- **`BlockProtectionListener`** — in `onBlockBreak`, before the existing non-owner cancel, add: if
  the breaker holds `enthusiamarket.admin.shop` and `AdminBreakMode.isActive(breaker)`, call
  `management.adminDelete(shop.id)`, message, and allow the break.
- **`SignPlaceListener`** — replace its four inline `event.line(i, …)` formats with
  `ShopSignRenderer.lines(shop)` applied to the `SignChangeEvent` (behaviour-preserving refactor).
- **`ShopEditMenu.open`** — its internal admin check currently accepts only
  `enthusiamarket.admin`; widen it to also accept `enthusiamarket.admin.shop` so an admin granted
  only the shop-moderation node can use `/shop admin view`.
- **`SearchResultsMenu`** — the result-click handler branches on
  `player.hasPermission("enthusiamarket.admin.shop")`: true → teleport to the sign location
  (centred, +0.5 x/z, same yaw); false → the existing coords-to-chat.
- **`ShopCommands`** — inject `LookAtShopResolver`, `AdminBreakMode`, `ShopManagementService`
  (already present), `ShopSignRenderer`; add the five `@Subcommand("admin …")` methods.
- **`build.gradle.kts`** — one new permission node.
- **`en_US.yml`** — a `shop.admin.*` block (chat messages) + one `gui.shop.search.teleported` key.

### Data flow — `/shop admin info`

```text
player runs /shop admin info
  → ShopCommands.adminInfo  (perm: enthusiamarket.admin.shop, player-only)
  → val b = player.getTargetBlockExact(6)
  → LookAtShopResolver.resolve(b?.world?.name, b.x, b.y, b.z)
      → ShopRepository.findBySign(...) ?: findByContainer(...).firstOrNull()
  → null → "shop.admin.no_target"
  → else → send chat info card (owner via Bukkit.getOfflinePlayer(owner).name, coords, direction,
            sellAmount×material → costAmount, trusted.size, frozen, searchEnabled)
```

### Data flow — `breakothers` delete

```text
admin runs /shop admin breakothers 10m → AdminBreakMode.enable(uuid, 10*60_000)
admin breaks any shop sign
  → BlockProtectionListener.onBlockBreak
      → shop found, breaker != owner
      → hasPermission(enthusiamarket.admin.shop) && AdminBreakMode.isActive(uuid)
          → management.adminDelete(shop.id); allow break; "shop.admin.breakothers.deleted"
      → else existing behaviour (owner+breakdelete delete, or cancel)
```

## 3. Error handling

- **No shop in crosshair / out of range** → `shop.admin.no_target` (one message for view/info/remove/fix).
- **Console sender** → all five subcommands are player-only (`view`/`info`/`remove`/`fix` need a
  raycast; `breakothers` toggles a player's mode) → `shop.cmd.players_only` (existing key).
- **`fix` with a missing container** → re-render the sign anyway, then send
  `shop.admin.fix.container_missing` suggesting `remove`.
- **`fix` where the sign block is no longer a sign** (replaced) → `shop.admin.fix.not_a_sign`.
- **`adminDelete` on a vanished shop id** → returns false → `shop.admin.remove.not_found`
  (defensive; the raycast already resolved a live shop).

## 4. Testing (TDD where it has logic)

| Unit | Tests |
|---|---|
| `AdminBreakMode` | enable/active, disable, expiry (mirror the BreakDeleteMode test). |
| `LookAtShopResolver` | sign block → shop; container block → first shop; air/null → null; unknown block → null. (Mock `ShopRepository`.) |
| `ShopSignRenderer` | `[SELL]`/`[BUY]` header per direction; `Nx <material>` line; price line; `[Shop]` line; unknown sell item falls back gracefully. |
| `ShopManagementService.adminDelete` | deletes a shop owned by someone else; returns false on missing id. |
| `BlockProtectionListener` | (covered by existing tests + manual) the admin-break branch is thin glue; no new unit test required beyond AdminBreakMode. |

GUI teleport/info-card rendering and the raycast call itself are thin Bukkit glue, verified in-game,
not unit-tested.

## 5. Out of scope

- Coordinate-argument targeting (look-at only).
- A GUI info card (chat card only; matches ItemShops `info`).
- `reload` (EM already has `/em reload`).
- Barter-aware `info`/`fix` (cost-item display) — SP3.
- Market-region admin tooling — SP4.
- `breakothers` persistence across restart (ephemeral, like `breakdelete`).

## 6. Build order (tasks)

1. Permission node + `AdminBreakMode` (+ test).
2. `LookAtShopResolver` (+ test).
3. `ShopSignRenderer` extraction (+ test) + `SignPlaceListener` refactor.
4. `ShopManagementService.adminDelete` (+ test).
5. `BlockProtectionListener` breakothers wiring + `ShopEditMenu.open` admin.shop widening.
6. `/shop admin` subcommands (view/info/remove/fix/breakothers) + `shop.admin.*` lang.
7. `SearchResultsMenu` admin-teleport branch + lang.
8. Final gate (`clean detekt test shadowJar`) + mark `docs/tasks.md`.
