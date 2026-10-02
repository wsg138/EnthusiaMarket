# Implementation — EnthusiaMarket

**Date:** 2026-05-24
**Status:** Bootstrap (emitted by `/spear:init`; extend as components land)
**Owner:** BadgersMC

## 1. Repo layout (canonical)

```
EnthusiaMarket/
├── src/main/kotlin/net/badgersmc/em/
│   ├── domain/             # rules of the game — zero framework imports
│   │   ├── stall/          # Stall, StallId, OwnerRef, RentTerms, StallState, StallRepository
│   │   ├── auction/        # Auction, Bid, AuctionState, AuctionRepository
│   │   ├── shop/           # ShopSign, SignDirection, SignRepository
│   │   └── ports/          # RegionProvider, EconomyProvider, GuildProvider
│   ├── application/        # use cases — imports domain only
│   │   └── ImportStallsService, (planned) RentCollectionService, AuctionLifecycleService, ShopTradeService
│   ├── infrastructure/     # adapters — imports anything
│   │   ├── persistence/    # Database, Migrations, *RepositorySql
│   │   ├── worldguard/     # @Component WorldGuardRegionProvider
│   │   ├── vault/          # @Service LazyEconomyProvider + VaultEconomyProvider
│   │   ├── lumaguilds/     # @Component LumaGuildsGuildProvider
│   │   ├── commands/       # @Command AdminCommands (Nexus Paper)
│   │   ├── (planned) listeners/, scheduler/, bedrock/
│   ├── config/              # @ConfigFile EnthusiaMarketConfig (Nexus DI)
│   └── EnthusiaMarket.kt   # JavaPlugin entry point; creates NexusContext, registers DI + commands
├── src/main/resources/
│   ├── plugin.yml
│   ├── config.yml
│   └── migrations/         # V001__*.sql ...
├── src/test/kotlin/net/badgersmc/em/
│   └── architecture/       # Konsist layer rule test
├── docs/
│   ├── tech-stack.md
│   ├── requirements.md
│   ├── implementation.md
│   └── tasks.md
└── build.gradle.kts
```

## 2. Layer Dependency Rules

The three-layer discipline SPEAR enforces. `/spear:arch` reads this exact section and blocks on violations.

| Layer | Concrete files | May depend on |
|---|---|---|
| `domain/` (rules-of-the-game) | `src/main/kotlin/net/badgersmc/em/domain/**` | nothing outside `domain/` + Kotlin stdlib |
| `application/` (use cases / workflow) | `src/main/kotlin/net/badgersmc/em/application/**` | `domain/` only |
| `infrastructure/` (adapters, frameworks, I/O) | `src/main/kotlin/net/badgersmc/em/infrastructure/**` + `di/**` + `EnthusiaMarket.kt` | anything |

Violations are reported as `file:line:symbol`. Suggested fixes: move the offending type, introduce a port interface in `domain/`, or relocate framework wiring to `infrastructure/`.

## Forbidden Domain Annotations

Framework annotations that must NOT appear on any type under `domain/**`. `/spear:arch` scans for these; the default denylist covers common JVM offenders. Extend the YAML list below for project-specific additions.

```yaml
# Default denylist (always active on JVM projects):
#   org.springframework.*
#   jakarta.persistence.*
#   javax.persistence.*
#   com.fasterxml.jackson.*
#   io.micronaut.*
#   lombok.*
#
# Project-specific patterns: domain must not import Bukkit, Paper, WG, Vault, or Koin.
forbidden:
  - org.bukkit
  - io.papermc
  - com.sk89q.worldguard
  - net.milkbowl.vault
  - org.koin
  - co.aikar.commands
  - co.aikar.idb
  - com.zaxxer.hikari
  - org.geysermc
  - net.badgersmc.nexus
```

## 3. Component design

### 3.1 Stall aggregate (domain)

Root entity for a market stall. Holds owner ref (player UUID or guild id), rent terms, state (vacant/rented/owned/default), and region binding.

- Layer: domain
- Ports / interfaces: `StallRepository`
- Adapters: `infrastructure/persistence/StallRepositorySql`
- Evidence sources consulted: `src/main/kotlin/net/badgersmc/em/domain/stall/Stall.kt`

### 3.2 Auction aggregate (domain)

Timed sale of an escrowed item with anti-snipe extension on late winning bids.

- Layer: domain
- Ports / interfaces: `AuctionRepository`
- Adapters: (planned) `infrastructure/persistence/AuctionRepositorySql`
- Evidence sources consulted: `src/main/kotlin/net/badgersmc/em/domain/auction/Auction.kt`

### 3.3 ShopSign (domain)

Sign registered to a stall region, buy or sell direction, item + price.

- Layer: domain
- Ports / interfaces: `SignRepository`
- Adapters: (planned) `infrastructure/persistence/SignRepositorySql`, listener in `infrastructure/listeners/SignPlaceListener`
- Evidence sources consulted: `src/main/kotlin/net/badgersmc/em/domain/shop/ShopSign.kt`

### 3.4 Ports (domain)

`RegionProvider` (WorldGuard adapter), `EconomyProvider` (Vault adapter), `GuildProvider` (LumaGuilds adapter).

- Layer: domain (port interfaces only)
- Adapters: `infrastructure/worldguard/`, `infrastructure/vault/`, `infrastructure/lumaguilds/`

### 3.5 ImportStallsService (application)

Idempotent: enumerate WG regions matching prefix, upsert Stall rows.

- Layer: application
- Inputs: `RegionProvider`, `StallRepository`, default `RentTerms`
- Evidence sources consulted: `src/main/kotlin/net/badgersmc/em/application/ImportStallsService.kt`

### 3.6 (planned) RentCollectionService (application)

Runs on a scheduler. For each rented/owned stall, debit owner via `EconomyProvider`; on failure mark default; on grace expiry call `Stall.evict()`.

### 3.7 (planned) AuctionLifecycleService (application)

Open, bid, close. Anti-snipe extension implemented in `Auction.placeBid()`; settlement on tick.

### 3.8 (planned) ShopTradeService (application)

Validate sign + actor, perform atomic item ↔ economy swap with rollback on failure.

### 3.9 EnthusiaMarket (infrastructure / JavaPlugin)

Bootstraps NexusContext with classpath scanning, opens Hikari datasource, runs migrations, registers Paper commands via Nexus's Brigadier system.

After registering the DataSource (and before commands/listeners construct any consumer) it builds the shop repository chain: `ShopRepositorySql` → `InMemoryShopLocationIndex` → `IndexedShopRepository`, rebuilds the index from `shopSqlRepo.all()`, and registers the decorator as the sole `ShopRepository` bean (REQ-281/282, PERF-4). `ShopRepositorySql` is deliberately NOT `@Repository` — nexus indexes a bean under its type + interfaces and `getBean(type)` throws on more than one match, so a scanned SQL repo plus the registered decorator would be ambiguous under `ShopRepository`.

### 3.10 ShopLocationIndex (domain port) + IndexedShopRepository (application)

Authoritative in-memory index of which container coordinates host shops, so the hopper-control hot path resolves shop status without a per-event DB query (REQ-281/282).

- Layer: domain (port `ShopLocationIndex`) + application (`InMemoryShopLocationIndex` adapter, `IndexedShopRepository` decorator)
- Ports / interfaces: `domain/shop/ShopLocationIndex` (`shopsAt` / `put` / `remove` / `rebuild`)
- Adapters: `application/InMemoryShopLocationIndex` (coord-keyed map, stdlib only); `application/IndexedShopRepository` decorates any `ShopRepository`, delegates every mutation and reconciles the index, and serves `findByContainer` from the index. Single choke point: all ~25 `ShopRepository` consumers inject the interface, so the decorator covers every mutation path transparently. Sync chosen over `ShopCreated/DeletedEvent` because those events fire on only some create/delete paths.
- Evidence sources consulted: `src/main/kotlin/net/badgersmc/em/domain/shop/ShopLocationIndex.kt`, `src/main/kotlin/net/badgersmc/em/application/IndexedShopRepository.kt`

### 3.11 Ownership integrity transition boundary (26.2 hardening)

Normal ownership changes are coordinated in the application layer instead of allowing each caller to independently remember shops, delegated members, rent state, IP reservations, and WorldGuard synchronization.

- Scope: buyout, direct sell-offer purchase, normal auction settlement, emergency-auction settlement/recovery, administrative eviction, and explicit release to `UNOWNED`.
- Domain invariant: an award to a different owner begins with an empty delegated-member set; a release to `UNOWNED` clears ownership/timing/bid/member state as one operation.
- Shop invariant: previous-owner non-admin shops do not transfer to the successor owner; administrative shops are preserved unless an explicit administrative workflow owns their cleanup.
- Region invariant: ownership awards use `RegionMemberSync`; its WorldGuard adapter already replaces owners and members rather than appending them.
- Emergency-auction invariant: the defaulter may remain recorded only as settlement provenance while `EMERGENCY_AUCTIONING`, but the stall no longer counts as actively held and the defaulter retains no delegated shop/region access.
- Recovery invariant: no-bid and orphan emergency paths use the same canonical release semantics as eviction rather than partial `copy(state = UNOWNED)` mutations.
- Reconciliation invariant: migration/repair logic is conservative, idempotent, and never infers guild/shop ownership from incompatible identifier types.

The Staff moderation provider added in PR #194 is a protected boundary, not another caller of the generic transition cleanup. Its PREPARED / MODERATION_HOLD / RESTORED / RELEASED / QUARANTINED lifecycle, snapshot checksums, moderation revisions, durable locks, player fences, exact shop-freeze restoration, and `MarketRegionAccessCoordinator` behavior remain authoritative under REQ-306 through REQ-313 and REQ-320.

The implementation sequence therefore begins with characterization tests around PR #194 before changing ownership code. Ordinary ownership services must continue to pass through `MarketMutationGate` / acquisition fencing before money or state changes, and repository writes must retain the optimistic moderation revision checks introduced by V028.

### 3.12 BulkRentExtensionService

Administrative deadline credit is an application service, not a synthetic rent payment. `/em rent extendall <duration>` accepts strict positive whole-number `m`, `h`, or `d` durations, selects unlocked `OWNED` and `GRACE` stalls, shifts `nextRentAt`, charges nobody, and isolates per-stall failures. When a deadline is absent, the legacy due-date estimate `ownerSince + collection interval` is used when available, with the command-time `Instant` only as the last-resort baseline. A GRACE stall whose shifted deadline is again in the future returns to `OWNED` through the normal state-change event so frozen shops are released consistently. The command requires `enthusiamarket.admin.rent` and reports updated, recovered, skipped, and failed counts.

The V029 reconciliation is the release-time companion to the runtime invariants above. It repairs only provably stale database state, skips live moderation reservations/holds, preserves guild/admin data, is idempotent, and deliberately leaves WorldGuard to `/em rg resync`. Operator rollout details live in [ownership-integrity-26.2.md](ownership-integrity-26.2.md).

## 4. Data flows

### 4.1 Stall import (REQ-002)

1. Operator runs `/em import`.
2. `AdminCommands.import()` calls `ImportStallsService.run()`.
3. Service queries `RegionProvider.regionsWithPrefix(world, prefix)`.
4. For each region, `StallRepository.upsert(Stall(...))` (idempotent by region id).
5. Service returns count; command replies.

### 4.2 Rent collection (REQ-003 → REQ-004)

1. Scheduler fires interval tick.
2. `RentCollectionService.tick()` iterates `StallRepository.allLeased()`.
3. For each stall: compute due, call `EconomyProvider.withdraw(owner, amount)`.
4. Success → record payment; failure → `Stall.markDefault(now)`.
5. If `now > stall.defaultedAt + gracePeriod` → `Stall.evict()`; persist.

### 4.3 Shop transaction (REQ-005 → REQ-006)

1. Listener catches `PlayerInteractEvent` on registered sign.
2. `ShopTradeService.execute(sign, actor)` validates ownership and inventory space.
3. Inside a try block: economy debit → item transfer → economy credit owner.
4. On exception: reverse any completed step (REQ-040).

### 4.4 Auction lifecycle (REQ-007 → REQ-009)

1. `/em auction start <duration> <price>` → escrow held item into auction lot.
2. `/em bid <id> <amount>` → `Auction.placeBid(...)` returns new state; anti-snipe extends `endsAt`.
3. Scheduler tick finds expired auctions → settle: item to bidder, fee to system, remainder to seller.

### 4.5 Bedrock form (REQ-011)

1. Player opens stall menu via command or sign click.
2. Bedrock dispatcher checks `FloodgateApi.isFloodgatePlayer(uuid)`.
3. If true → render Cumulus form; else → Bukkit inventory GUI.

### 4.6 Hopper control hot path (REQ-281/282)

1. `InventoryMoveItemEvent` fires (once per hopper transfer tick, the highest-frequency event on the server).
2. `HopperControlListener` resolves source + destination container blocks and calls `shopRepository.findByContainer(...)`.
3. The injected `IndexedShopRepository` answers from `InMemoryShopLocationIndex.shopsAt(...)` — an O(1) coordinate lookup, no DB query on the server thread.
4. If a shop is found, the listener cancels the move when `hopperAllowOut` (source) / `hopperAllowIn` (destination) is false; otherwise the event passes through.
5. The index stays correct because every shop mutation flows through `IndexedShopRepository` (4.3 and the management/guild services), and `onEnable` rebuilds it from persistence.

## 5. Briefing contract for subagent dispatch

Every worker dispatch (`Agent` tool call) for implementation work carries:

- Exact file paths to create / modify.
- Pre-verified signatures (from context7, library source on disk, or `mgrep`).
- The failing test (path + test name) for TDD tasks.
- Acceptance criteria — which test goes green; which files MUST NOT change.
- Forbidden actions — scope fences.
- The task's `Evidence:` block verbatim.

Tasks whose full briefing exceeds ~1500 tokens are decomposed further by `/spear:spec` before dispatch.

## 6. Versioning

Semantic versioning. Start at `0.1.0`. Bump major on breaking public-API or DB schema change (migrations always additive within a major).

## 7. Out of scope (this doc)

- Per-component code-level docs — owned by each component's own KDoc.
- CI configuration — owned by `tech-stack.md` §CI and the workflow file itself.
