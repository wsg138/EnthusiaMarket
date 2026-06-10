# EnthusiaMarket — Fresh Audit (runtime wiring + money paths)

> **Status update (same day):** W-1, W-2, W-3, M-2, M-3, M-4, and N-1 are FIXED
> on branch `fix/runtime-wiring` (TDD; guarded by `architecture/ListenerWiringTest`
> and `architecture/SqlPortabilityTest`). M-1 is documented in onEnable (nexus-side
> fail-closed fix still open). M-5 still requires redeploying the patched build to
> production. Open: N-2..N-7.

**Date:** 2026-06-09
**Auditor:** Claude (Fable 5), local session
**Branch:** fix/audit-final
**Scope:** Full `src/main/kotlin` (148 files) with emphasis on bean wiring, money paths, persistence, and live-server impact. Independent of EM-FINAL-AUDIT.md (same-day); overlapping findings are cross-referenced.

---

## Summary

| Severity | Count |
|----------|-------|
| Critical | 3 |
| Major | 5 |
| Minor | 7 |

The headline is **not** a money-path bug: it is that a large slice of the plugin is
**never wired at runtime**. Nexus DI (v2.2.1) is lazy — beans are only constructed
when something requests them — and `registerNexusListeners` only instantiates
classes annotated `@net.badgersmc.nexus.paper.listeners.Listener`. Every
`@Component` bean that relies on a `@PostConstruct` self-registration block and has
no inbound dependency is therefore **never constructed at all**.

Evidence chain:
- `NexusContext.initialize` (v2.2.1, verified via `git show v2.2.1:...`):
  *"Register all definitions (but don't create instances yet — factories are lazy)"*.
- `ListenerRegistry.registerNexusListeners` scans **only**
  `getClassesWithAnnotation(Listener::class)`.
- `EnthusiaMarket.onEnable` calls `getBean` only for: config, ShopTransactionRepository,
  GuildDissolutionService, GuildProvider, ParticleBorderService, ShopRepository.
- Repo-wide grep: nothing else references the dead classes below.

---

## Critical

### W-1 — Nine listeners are never registered (lazy DI + missing `@Listener` annotation)

These are `@Component` + `@PostConstruct fun register()` only — the old pre-nexus-2.x
pattern. Nothing ever requests these beans, so `@PostConstruct` never runs and the
listener is never registered with Bukkit:

| Class | What silently doesn't work |
|---|---|
| `ShopInteractListener` | **Main container-shop entry point (REQ-013)** — right-click shop sign → PurchaseMenu / info card |
| `BlockProtectionListener` | Shop sign/container break protection (incl. AdminBreakMode / BreakDeleteMode flows) |
| `ContainerStockListener` | stock_count denormalization on container edits → `/shop search` counts go stale |
| `HopperControlListener` | hopperAllowIn/Out enforcement — hoppers can drain shop containers |
| `ExplodeCleanupListener` | shop cleanup after explosions |
| `ShopCreateListener` | shop creation flow listener |
| `SignInteractListener` | legacy sign trades (REQ-006) — also see W-3 |
| `GuildPolicyAnnounceListener` | policy-change announcements |
| `GuildShopPolicyEntryListener` | embargo/tariff entry messaging |

**Fix:** add `@net.badgersmc.nexus.paper.listeners.Listener` to each (they already
implement Bukkit `Listener`), delete the `@PostConstruct` self-registration blocks.
The Vault gate in `SignInteractListener.register()` needs preserving (move the
`vaultHealth` check into the event handler or keep PostConstruct semantics).

### W-2 — RentScheduler and AuctionScheduler are never constructed

Same root cause as W-1: both are `@Component` + `@PostConstruct fun start()`, and
nothing `getBean`s them. Consequences:

- **Rent is never collected.** `nextRentAt` passes and nothing happens; no GRACE,
  no evictions. Stall ownership is effectively free after the initial buy.
- **Auctions never settle.** `settleExpired()` never runs; expired auctions stay
  OPEN forever, stalls stuck in AUCTIONING (only escape is the owner's manual
  cancel, which mass-auctioned UNOWNED stalls don't have — owner is NONE).

**Fix:** `ctx.getBean<RentScheduler>()` + `ctx.getBean<AuctionScheduler>()` in
`onEnable` (or give nexus an eager-singleton/`@Eager` mechanism).

### W-3 — `ItemProvider` port has no implementation

`ShopTradeService` (legacy sign trades) injects `domain.ports.ItemProvider`; grep
finds **no implementing class anywhere in src/main**. Today this is masked by W-1
(SignInteractListener is never constructed, so the dependency chain is never
resolved) — but the moment W-1 is fixed naively, `getBean(SignInteractListener)`
will throw `No bean found of type: ItemProvider` and (because
`registerNexusListeners` only WARNs per-listener, see M-1) the sign-trade path will
*still* be silently dead.

Also note the legacy path's semantics: `executeSell` **mints** items via
`giveItemToPlayer` with no stock source, `executeBuy` **destroys** taken items with
no delivery to the owner — admin-shop behavior on player stalls. Decide whether to
(a) delete the legacy sign-trade path + ShopTradeService + SignInteractListener
outright (container shops + purchase signs cover the use cases), or (b) implement
the port with real container backing. (a) looks right.

---

## Major

### M-1 — `registerNexusListeners` is fail-open, but onEnable claims fail-closed

`EnthusiaMarket.onEnable` wraps the call in try/catch with the comment *"Fail-closed:
any listener that can't be resolved/registered will disable the plugin"* — but the
nexus implementation catches per-listener exceptions and logs a WARNING, then
continues. A listener whose constructor deps can't resolve disappears silently
(exactly the W-3 scenario). Fix in nexus: count failures and either throw or expose
them; at minimum the EM comment is wrong.

### M-2 — No bid-time escrow/balance check → insolvent winner wedges settlement

`AuctionLifecycleService.placeBid` never checks the bidder's balance (EM-FINAL-AUDIT
F-005 was "fixed" by charging at settlement instead). But `settleWithWinner` step 0
**throws** when the withdraw fails, `settleExpired` counts an error, and the auction
stays OPEN/expired — so it **retries every scheduler tick forever** (once W-2 is
fixed). A player with 0 coins can win any auction and permanently wedge the stall in
AUCTIONING with log spam every 20s. Fix: on withdraw failure, treat like the
limit-reject path (close auction, revert stall to UNOWNED, optionally fall back to
next-highest bid).

### M-3 — SQLite-only `ON CONFLICT` breaks the advertised MariaDB mode

`ShopVaultRepositorySql.deposit` and `GuildTradePolicyRepositorySql.upsert` use
`ON CONFLICT ... DO UPDATE` — SQLite syntax. Config offers `database.type: mariadb`;
on MariaDB both throw SQLException at first use (vault deposits = barter trades
break mid-flight; `executeTrade` compensates, but the feature is dead). Use
`ON DUPLICATE KEY UPDATE` per dialect or a portable upsert.

### M-4 — Eviction leaves bound shops attached to the stall

`RentCollectionService.evict`, `StallEvictionService.evict`, and guild dissolution
do **not** wipe `shop_items` rows bound to the stall (sellback does;
StallEvictionService doc says this is intentional). Consequence: next buyer of the
stall inherits the previous owner's shop rows. `ContainerTradeService.resolveOwnerUuid`
resolves the **stall** owner, so with schematics disabled (containers physically
survive), the new owner's account receives proceeds from — and players can buy out
stock of — chests the previous owner filled. With schematics enabled the rows are
merely orphans (container missing). Recommend wiping or freezing bound shops on any
ownership reset, not just sellback.

### M-5 — Plugin is live on production with this wiring

The production server (26 players online at audit time) lists EnthusiaMarket as an
enabled Paper plugin. If the deployed jar is built from this source, then today, in
production: shops can't be opened by sign-click, shop containers are hopper-drainable
and breakable (modulo WG region overlap), and rent/auction settlement never run.
**Verify the deployed build** (boot log should read `Registered N @Listener beans` —
expect ~10 with W-1 unfixed vs ~19 fixed; and EM rent/auction settlement log lines
should appear over time).

---

## Minor

| ID | File | Issue |
|----|------|-------|
| N-1 | `ShopRepositorySql.mapRow` | `guild_id`/`creator_id` still strict-`UUID.fromString` — one corrupt value throws and poisons whole-list loads (F-007 fixed only `trusted`). |
| N-2 | `VaultEconomyProvider` | `balance()` `.toLong()` truncation (F-008, unfixed — benign: makes affordability checks stricter). No negative-amount guard on withdraw/deposit (defense-in-depth, see N-3). |
| N-3 | `SellOfferService.purchase` | `total = price + tax` can overflow Long; `create` accepts any positive price up to Long.MAX_VALUE. Overflow → negative withdraw passed to Vault. Add a config max price. |
| N-4 | `AuctionScheduler` | Doc says "runs asynchronously"; code uses `runTaskTimer` (sync). DB + economy on main thread each tick. |
| N-5 | `AuctionLifecycleService.settleWithWinner` | System mass-auction settle (owner NONE) logs "seller payment failed" warning on every win — expected-path noise; money is intentionally sunk. |
| N-6 | `RentCollectionService.handleFailure` | OWNED→GRACE overwrites `ownerSince` with now() to repurpose it as the grace timer — original claim date lost; semantic overload worth a dedicated `graceSince` column. |
| N-7 | `ShopVaultRepositorySql.withdraw` | Read-modify-write tx without `SELECT ... FOR UPDATE`; racy under MariaDB pool >1 (theoretical — calls are main-thread today). |

---

## Confirmed fixed from EM-FINAL-AUDIT (spot-checked)

- F-001/F-004 partial-fit dupe guards in `ContainerTradeService` — present, with comments.
- F-009 GUILD self-trade bypass — guild stalls now rejected at the legacy sign path.
- F-010 rent tick batch kill — per-stall try/catch now.
- F-007 (`trusted`) and F-016 (param index) in `ShopRepositorySql` — fixed.
- F-006 tariff confiscation — capped at `MAX_TARIFF_PCT = 99` by design.

## Not covered this pass

GUI menus (`interaction/gui/*`), Bedrock forms, WorldGuard/WorldEdit adapters,
PAPI placeholders, ImportStallsService, ShopManagementService internals, migrations.
SQL injection: clean — repo-wide grep found no string interpolation into SQL.
