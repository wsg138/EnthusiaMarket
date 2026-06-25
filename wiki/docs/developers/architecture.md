---
title: Architecture
audience: dev
topic: architecture
summary: High-level architecture of the EnthusiaMarket plugin — hexagonal design, layer structure, and key components.
keywords: [architecture, hexagonal, layers, domain, ports, adapters]
related: [domain, application, infrastructure]
updated: 2026-06-25
---

# Architecture

EnthusiaMarket follows a hexagonal (ports-and-adapters) architecture with three strict layers. This page gives the high-level view.

## Layer structure

```text
┌──────────────────────────────────────────┐
│         infrastructure/                   │
│  adapters, frameworks, I/O, commands      │
│  (can import anything)                    │
├──────────────────────────────────────────┤
│         application/                      │
│  use cases, workflow, services            │
│  (can import domain/ + stdlib only)       │
├──────────────────────────────────────────┤
│         domain/                           │
│  rules of the game, ports, aggregates     │
│  (can import domain/ + Kotlin stdlib)     │
└──────────────────────────────────────────┘
```

### Domain layer

**Path:** `src/main/kotlin/net/badgersmc/em/domain/`

Pure Kotlin. No framework imports — no Bukkit, no Paper, no WorldGuard, no Koin, no Hikari. Contains:

- **Entities:** `Stall`, `Shop`, `Auction`, `Bid`, `RentTerms`
- **Value objects:** `StallId`, `OwnerRef`
- **Enums:** `StallState`, `SignDirection`, `AuctionState`, `OwnerType`, `PolicyKind`, `GuildPermission`, `RentTerms.Mode`
- **Repository ports:** `StallRepository`, `ShopRepository`, `AuctionRepository`, `ShopVaultRepository`
- **Provider ports:** `RegionProvider`, `EconomyProvider`, `GuildProvider`, `RegionProvisioner`, `SchematicService`

### Application layer

**Path:** `src/main/kotlin/net/badgersmc/em/application/`

Use cases and business workflows. Depends on domain ports only (not implementations). Contains:

- `ImportStallsService` — register WG regions as stalls
- `RentCollectionService` — collect rent, handle eviction
- `AuctionLifecycleService` — manage auction open/close/settle
- `StallBuyoutService` — buy UNOWNED stalls
- `StallRentExtensionService` — extend rent on owned stalls
- `StallSellbackService` — voluntary stall relinquish
- `ContainerTradeService` — BUY/SELL/TRADE transactions
- `LimitResolutionService` — effective stall limit calculation
- `ShopManagementService` — shop CRUD, trust, search
- `ShopFactory` — build Shop instances from GUI input
- `IndexedShopRepository` — in-memory shop-container index decorator
- `ShopLocationIndex` / `InMemoryShopLocationIndex` — hot-path lookup

### Infrastructure layer

**Path:** `src/main/kotlin/net/badgersmc/em/infrastructure/`

Framework adapters. Imports anything — Bukkit, Paper, WorldGuard, Vault, Koin, Hikari, nexus-paper.

- **persistence/:** `*RepositorySql` with SQLite and MariaDB support
- **worldguard/:** `WorldGuardRegionProvider`, `WorldGuardRegionProvisioner`, `WorldGuardRegionMemberSync`
- **vault/:** `LazyEconomyProvider`, `VaultEconomyProvider`
- **lumaguilds/:** `LumaGuildsGuildProvider`
- **commands/:** `AdminCommands`, `ShopCommands`, `ShopHelpCommands`, `VaultCommands`, `StoreCommands`
- **listeners/:** `SignPlaceListener`, `ContainerStockListener`, `HopperControlListener`, `PurchaseSignClickListener`, `BlockProtectionListener`, `ShopCreateListener`, `GuildDisbandedEventListener`
- **interaction/:** GUI menus (`CreateShopMenu`, `PurchaseMenu`, `OwnedShopsMenu`, `ShopEditMenu`, `SearchResultsMenu`) and Bedrock forms

## Key design decisions

1. **Repository as decorator.** `IndexedShopRepository` wraps `ShopRepositorySql` in the application layer. All ~25 consumers inject the `ShopRepository` interface — DI resolves the decorator. This makes every mutation path go through the in-memory index with zero call-site edits.

2. **DI via nexus-core.** The plugin uses BadgersMC's nexus-core DI framework (not Koin directly). Beans are registered in `EnthusiaMarket.onEnable` after the DataSource.

3. **Architecture enforcement.** A Konsist test (`LayerRulesTest.kt`) runs in CI to enforce layer boundaries. Framework annotations in `domain/` are denied (Bukkit, Paper, WG, Vault, Koin, Hikari, nexus).

4. **WorldGuard as hard dependency.** Stall regions are WorldGuard regions. The plugin's region import provisions flags and ACLs. No standalone region system.

5. **SQLite + MariaDB.** SQLite for single-server / testing. MariaDB for production with connection pooling. Schema migrations run on enable.
