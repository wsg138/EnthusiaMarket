# ItemShops Parity — Sub-project 6: Misc / Integration

**Date:** 2026-06-04
**Status:** Approved (brainstorming complete)
**Owner:** BadgersMC
**Plugin:** EnthusiaMarket (EM)

## 0. Context — the ItemShops parity programme

EM deprecates **ItemShops** (p2wn). Guiding rule: **faithful parity**. The effort is six
sub-projects, build order **1 → 2 → 5 → 6 → 3 → 4**:

1. Shop management commands — done (PR #26)
2. Shop search — done (PR #28)
3. Profits vault (item barter + item vault) — later
4. Limits + market regions — later
5. Admin tooling — done (PR #31)
6. **Misc / integration** ← *this spec*

This sub-project ports ItemShops' remaining integration surface onto EM's **Vault-money** model
and must not block the later barter addition (SP3).

## 1. Scope of this sub-project

Four features. The **transaction table is the spine**: history, online notifications, offline
notifications, and one placeholder all read it.

| Feature | Summary |
|---|---|
| Transaction history | Persist every completed shop trade; `/shop history [page]` shows the sender's own shop sales. |
| Owner sale notifications | Notify a shop owner when someone trades at their shop — live if online, summarised on next join if offline. |
| PAPI placeholders | Expose `%enthusiamarket_shops_owned%`, `%..._shops_total%`, `%..._sales_unseen%`. |
| Sign-click info card | Shift + right-click a shop sign → chat info card (item, price, direction, owner, live stock) without opening the trade GUI. |

### Out of scope
- A separate admin transaction-history query (admins already have `/shop admin info`; `/shop history`
  is own-shops only).
- Barter-aware history/placeholders (cost-item) — SP3.
- Per-player shop **limits** and `can_create` placeholders — SP4.
- Notification channels other than chat (no Discord/webhook).
- Exporting history to file.

## 2. Architecture

Hexagonal/SPEAR. One new persistence aggregate (the transaction log) feeds three of the four
features; the placeholder + info-card features are thin read paths.

### The transaction log (spine)

- **`ShopTransaction`** (`domain/shop/`) — data class: `id, shopId, owner(UUID), buyer(UUID),
  direction(SignDirection), item(String materialName), quantity(Int), totalPrice(Long),
  createdAt(Long epoch ms), notified(Boolean)`.
- **`ShopTransactionRepository`** (`domain/shop/`) — port:
  `record(tx): ShopTransaction`, `findByOwner(owner, limit, offset): List<ShopTransaction>`,
  `countUnnotified(owner): Int`, `markNotified(owner)`, `prune(beforeMs): Int`.
- **`ShopTransactionRepositorySql`** (`infrastructure/persistence/`) — JDBC impl over a new
  `shop_transactions` table (migration **V015**), mirroring `ShopRepositorySql` conventions.

### Recording (event-driven)

- **`PostShopTransactionEvent`** gains two fields — `shopId: Long` and `direction: SignDirection`
  (added with defaults so existing construction/integrators keep compiling). `ContainerTradeService`
  already fires this event on every successful buy/sell; it passes the new fields.
- **`ShopTransactionRecorder`** (`infrastructure/listeners/`) — `@Listener` on
  `PostShopTransactionEvent` → builds a `ShopTransaction` (notified = false) and calls
  `repository.record(...)`. Infrastructure owns the DB write; the trade path stays clean.

### Notifications (drain the `notified` flag)

- **`ShopSaleNotifier`** — `@Listener` on `PostShopTransactionEvent` → if `event.landlordId` (the
  shop owner) is online, send a chat line and `markNotified(owner)` so the join path won't repeat it.
- **`ShopSaleJoinNotifier`** — `@Listener` on `PlayerJoinEvent` → `countUnnotified(player.uniqueId)`;
  if > 0, send a one-line "N sales while you were away" summary and `markNotified(player.uniqueId)`.
- Gated by `config.shop.notify.enabled` (default true). The `notified` flag is the single source of
  "unseen", so online and offline paths never double-notify.

### Read paths

- **`/shop history [page]`** — a `@Subcommand` on the existing `ShopCommands`, `@Permission`
  `enthusiamarket.shop.use`; pages `findByOwner(sender, PAGE, offset)` into a chat list (10/page).
- **`ShopPlaceholders`** (`infrastructure/papi/`) — `@PapiExpansion(identifier = "enthusiamarket")`
  + `@Component` + `PlaceholderResolver`. `resolve(player, params)` maps:
  `shops_owned` → `shopRepository.findByOwner(uuid).size`; `shops_total` → `shopRepository.all().size`;
  `sales_unseen` → `transactionRepository.countUnnotified(uuid)`; unknown → null. A
  `registerNexusExpansions("net.badgersmc.em", classLoader, ctx)` call is added to `onEnable` (EM
  doesn't wire nexus-papi yet).
- **Sign-click info card** — in `ShopInteractListener.onSignRightClick`, before opening the menu:
  if `event.player.isSneaking`, send the info card (a pure `ShopInfoCard.lines(shop, stock)` builder)
  and `return`. Live stock reuses the container-read already used by `SearchResultsMenu`.

### Retention

- `prune(now - retentionDays.days)` runs once in `onEnable` (after migrations), gated by
  `config.shop.history.retention_days` (default 30; `0` disables pruning).

## 3. Data flow — a sale

```text
buyer clicks a [SELL] shop → ContainerTradeService.executeSell succeeds
  → fires PostShopTransactionEvent(buyer, owner, item, qty, price, shopId, direction)
      → ShopTransactionRecorder      → repository.record(notified = false)
      → ShopSaleNotifier             → owner online? chat + markNotified(owner)
owner logs in later (was offline)
  → ShopSaleJoinNotifier             → countUnnotified(owner) > 0 → summary + markNotified(owner)
owner runs /shop history            → findByOwner(owner, 10, page*10) → chat list
%enthusiamarket_sales_unseen%       → countUnnotified(owner)
```

## 4. Error handling

- **No PlaceholderAPI installed** → `registerNexusExpansions` no-ops (already guarded in nexus-papi);
  placeholders simply don't register.
- **DB write failure in the recorder** → logged, swallowed; a failed *history* write must never roll
  back or interrupt the completed trade (money + items already moved).
- **`/shop history` with no sales** → `shop.history.empty`.
- **Notifications disabled** (`config.shop.notify.enabled = false`) → both listeners early-return.
- **Guild-owned shop** → `owner` is the player UUID on the row as today; guild bank routing is
  unchanged. (No guild-wide broadcast in scope.)

## 5. Testing (TDD where there is logic)

| Unit | Tests |
|---|---|
| `ShopTransactionRepositorySql` | round-trip a row; `countUnnotified` before/after `markNotified`; `findByOwner` paging + newest-first order; `prune` deletes only older rows. |
| `ShopPlaceholders` | `shops_owned` / `shops_total` / `sales_unseen` resolve via mock repos; unknown key → null; null player → null where player-scoped. |
| `ShopInfoCard` | pure line builder: item, `Nx → price`, direction, owner, stock. |
| `/shop history` formatting | header + line rendering (pure, or thin). |

Notifier listeners and the raycast/PAPI registration are thin Bukkit glue, verified in-game.

## 6. Build order (tasks)

1. `ShopTransaction` + V015 migration + `ShopTransactionRepository(Sql)` (+ persistence test).
2. Enrich `PostShopTransactionEvent` (shopId, direction) + wire `ContainerTradeService` + `ShopTransactionRecorder` listener.
3. `/shop history [page]` command + `shop.history.*` lang.
4. Owner notifications: `ShopSaleNotifier` + `ShopSaleJoinNotifier` + `shop.notify.*` lang + `config.shop.notify.enabled`.
5. `ShopPlaceholders` `@PapiExpansion` + `registerNexusExpansions` in `onEnable` (+ resolver test).
6. Sign-click info card: `ShopInfoCard` builder + `ShopInteractListener` sneak branch + `shop.info.*` lang.
7. Retention: `config.shop.history.retention_days` + `prune` on enable.
8. Final gate (`clean detekt test shadowJar`) + mark `docs/tasks.md`.
