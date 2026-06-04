# ItemShops Parity — Sub-project 3: Barter / Profits Vault

**Date:** 2026-06-04
**Status:** Approved (brainstorming complete)
**Owner:** BadgersMC
**Plugin:** EnthusiaMarket (EM)

## 0. Context — the ItemShops parity programme

EM deprecates **ItemShops** (p2wn). Guiding rule: **faithful parity**. Six sub-projects, build
order **1 → 2 → 5 → 6 → 3 → 4**:

1. Shop management — done (PR #26)
2. Shop search — done (PR #28)
3. **Profits vault (item barter + item vault)** ← *this spec*
4. Limits + market regions — later
5. Admin tooling — done (PR #31)
6. Misc/integration — done (PR #33)

This is the first of the two **architectural forks** held to the end. It adds item-for-item shops
**alongside** the existing Vault-money shops without disturbing them.

## 1. Scope of this sub-project

A new sign keyword **`[TRADE]`** creates a barter shop: the owner sells `M × sellItem` from the
linked chest, the buyer pays `N × costItem`, and the payment items accumulate in the owner's
**per-owner vault**, withdrawn via **`/shopvault`**.

| Concept | Behaviour |
|---|---|
| `[TRADE]` sign | Third direction beside money `[SELL]`/`[BUY]`. Line 2 = sell amount `M` (sell item from hand); line 3 = `"N material"` → cost = `N ×` that material; line 4 → `[Shop]`. |
| Barter trade | Buyer right-clicks → pays `N × costItem` (into owner's vault), receives `M × sellItem` from the chest. No economy. Full rollback on failure. |
| Profits vault | Per-owner accumulator of collected payment items, DB-backed, unbounded (pure sink). |
| `/shopvault` | Paginated GUI; click withdraws one stack, shift-click withdraws all that fit; inventory-full leaves the remainder. |
| Edit menu | For a `[TRADE]` shop, the cost section sets the cost item **from hand** (for enchanted/NBT costs) + quantity controls. |

### Out of scope
- `[BUY]`-direction barter (owner pays items from the vault — vault as a *source*). `[TRADE]` is sell-for-items only; the vault is a pure sink.
- **Guild-owned** barter — guild-owned stalls reject `[TRADE]` at creation; the edit menu won't offer item-cost for them. (No guild-shared item vault; deferred.)
- Migrating `Shop.sellItem`/`costItem` off the legacy `ItemStackSerializer` (separate concern; live data).
- Barter-aware `/shop search buy`-mode (the SP2 no-op) and barter tax — future polish.

## 2. Architecture

Hexagonal/SPEAR. One new persistence aggregate (the vault) + a new trade path; money shops are
untouched.

### Serialization decision (Paper 1.21.11)

The vault uses Paper's **`ItemStack.serializeAsBytes()` / `deserializeBytes(byte[])`** (NBT, data-
component-aware, run through the game's data converter — Paper's javadoc calls the older
`BukkitObjectOutputStream` path "dangerous"). The vault's aggregation key is
`Base64(item.serializeAsBytes())`: identical items serialize to identical bytes **within a game
version**, so deposits merge. Residual caveat: the bytes embed a `DataVersion`, so a Minecraft
upgrade can split old vs new rows of the "same" item — cosmetic only (both withdraw fine because
`deserializeBytes` migrates), self-healing as rows drain. `Shop.sellItem`/`costItem` keep the
existing `ItemStackSerializer` (legacy compat).

### Model

- **`SignDirection`** gains `TRADE`. `BUY`/`SELL` remain money. The enum value is the barter
  discriminator — no new `Shop` column; `direction` already persists (V012). Adding the value makes
  every `when (direction)` non-exhaustive until updated (compiler-enforced coverage).
- **`Shop`** — for `direction == TRADE`, `costItem` (base64, via `ItemStackSerializer`) holds the
  **real** cost item and `costAmount` its quantity (vs the emerald-display + money number on money
  shops). `init` already requires `costAmount > 0`.

### New units

- **`VaultItem`** (`domain/shop/`) — `data class(owner: UUID, itemBytes: String /* Base64 NBT */, amount: Int)`.
- **`ShopVaultRepository`** (`domain/shop/`) — port: `deposit(owner, itemBytes, amount)` (upsert-add),
  `findByOwner(owner): List<VaultItem>`, `withdraw(owner, itemBytes, amount): Int` (decrement, delete
  at zero; returns amount actually removed).
- **`ShopVaultRepositorySql`** (`infrastructure/persistence/`) — JDBC over `shop_vault` (V016),
  primary key `(owner, item)`, `amount` accumulated.
- **`ShopVaultService`** (`application/`) — thin: `deposit(owner, ItemStack, qty)` serializes via
  `serializeAsBytes`; `contents(owner): List<Pair<ItemStack, Int>>` deserializes for the GUI;
  `withdraw(owner, ItemStack, qty)`.
- **`ShopVaultMenu`** (`interaction/gui/`) — paginated chest GUI of the owner's vault stacks.

### Changed units

- **`ContainerTradeService.executeTrade(shop, buyerUuid): ContainerTradeResult`** — barter path
  (no economy): verify buyer holds `N × costItem` (`containsAtLeast`), chest holds `M × sellItem`;
  remove cost from buyer → `vaultService.deposit(owner, costItem, N)`; move sell items chest→buyer;
  full rollback on any leg failing. Injects `ShopVaultService`.
- **`PurchaseMenu`** — `when (shop.direction)` gains `TRADE -> executeTrade`. The cost slot already
  renders `costItem`; lore reads "you pay N×".
- **`SignPlaceListener`** — `[TRADE]`/`TRADE` → `SignDirection.TRADE`; parse line 3 as `"N material"`
  (`Material.matchMaterial`) → `costItem = serialize(ItemStack(material, 1))`, `costAmount = N`;
  sell item from hand as today. **Reject `[TRADE]` on a guild-owned stall** (use the existing
  guild-ownership check) → error message. Bad line 3 → error.
- **`ShopSignRenderer`** — render the `TRADE` cost line as `Nx costmaterial` instead of a price
  (extend the signature to take a cost-display string; money shops keep `$price`).
- **`ShopEditMenu`** — for `direction == TRADE`, the cost section becomes "set cost item from hand"
  + quantity controls (replaces money +/-). Guild-owned TRADE shops never reach this (barred at
  creation), so no guild branch needed here.
- **`ShopCommands`** *(or a new `VaultCommands`)* — `/shopvault` opens `ShopVaultMenu`.
- **`build.gradle.kts`** — new node `enthusiamarket.shop.vault` (default `true`).
- **`en_US.yml`** — `shop.trade.*`, `shop.create.*` (trade errors), `gui.vault.*`.

### Data flow — a `[TRADE]` sale

```text
buyer right-clicks a [TRADE] sign → PurchaseMenu (TRADE) → confirm
  → ContainerTradeService.executeTrade(shop, buyer)
      → buyer has N×costItem?  chest has M×sellItem?      (else fail, no mutation)
      → remove N×costItem from buyer
      → vaultService.deposit(owner, costItem, N)          (Base64(serializeAsBytes) key, upsert-add)
      → remove M×sellItem from chest → buyer inventory
      → any leg fails → rollback all prior legs → CompensationFailed
owner runs /shopvault → ShopVaultMenu → click a stack
  → vaultService.withdraw(owner, item, min(stackSize, amount)) → buyer inventory (partial if full)
```

## 3. Error handling

- **Buyer lacks cost items** → `shop.trade.insufficient_cost`. **Chest out of stock** → `shop.trade.out_of_stock`.
- **Frozen / invalid amounts** → existing shop checks apply.
- **Rollback failure** → `ContainerTradeResult.CompensationFailed` (same surface as the money path).
- **`[TRADE]` on a guild-owned stall** → `shop.create.no_guild_trade`; sign not registered.
- **`[TRADE]` line 3 not `"N material"`** → `shop.create.invalid_trade_cost`.
- **`/shopvault` empty** → `gui.vault.empty` (or an empty GUI with a hint).
- **Withdraw with full inventory** → withdraw what fits, leave the remainder, message the partial.

## 4. Testing (TDD where there is logic)

| Unit | Tests |
|---|---|
| `ShopVaultRepositorySql` | deposit aggregates the same item key; deposit of a different item is a separate row; withdraw decrements and deletes at zero; `findByOwner`. |
| `ShopVaultService` | `serializeAsBytes` round-trips through deposit→contents (MockBukkit); withdraw returns the amount removed. |
| `ContainerTradeService.executeTrade` | success removes cost + deposits to vault + moves stock; insufficient cost → fail, no mutation; empty chest → fail; deposit/move failure → rollback. |
| `SignPlaceListener` (trade parse) | `"16 diamond"` → costItem/costAmount; bad line 3 → error; guild-owned stall → rejected. |
| `ShopSignRenderer` | `TRADE` line renders `Nx material`; money lines unchanged. |

GUI withdraw interaction and the raycast are thin Bukkit glue, verified in-game.

## 5. Build order (tasks)

1. `SignDirection.TRADE` + make existing `when (direction)` blocks exhaustive (compile-only).
2. `VaultItem` + `ShopVaultRepository` + V016 + `ShopVaultRepositorySql` (+ persistence test).
3. `ShopVaultService` (serializeAsBytes deposit/contents/withdraw) (+ test).
4. `ContainerTradeService.executeTrade` barter path + vault deposit (+ test).
5. `PurchaseMenu` TRADE routing + cost lore.
6. `SignPlaceListener` `[TRADE]` parse + guild-owned rejection + `ShopSignRenderer` trade line.
7. `/shopvault` command + `ShopVaultMenu` GUI + `enthusiamarket.shop.vault` node.
8. `ShopEditMenu` cost-item-from-hand for TRADE shops + `shop.trade.*`/`gui.vault.*` lang.
9. Final gate (`clean detekt test shadowJar`) + mark `docs/tasks.md`.
