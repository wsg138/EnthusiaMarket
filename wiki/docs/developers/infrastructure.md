---
title: Infrastructure layer
audience: dev
topic: infrastructure
summary: Infrastructure adapters — persistence, commands, listeners, DI wiring, and integrations.
keywords: [infrastructure, adapters, persistence, commands, listeners, di]
related: [architecture, domain, application]
updated: 2026-06-25
---

# Infrastructure layer

The infrastructure layer (`infrastructure/`) contains all framework adapters. It can import anything.

## Persistence (`infrastructure/persistence/`)

SQL implementations of domain repository ports.

| Class | Implements | Notes |
|-------|-----------|-------|
| `ShopRepositorySql` | `ShopRepository` | Decorated by `IndexedShopRepository` |
| `StallRepositorySql` | `StallRepository` | Direct — no decorator needed |
| `AuctionRepositorySql` | `AuctionRepository` | Direct |
| `ShopVaultRepositorySql` | `ShopVaultRepository` | Direct |

All use **prepared statements** (no raw SQL concatenation). SQL is portable between SQLite and MariaDB — tested via `SqlPortabilityTest`.

## WorldGuard adapters (`infrastructure/worldguard/`)

| Class | Purpose |
|-------|---------|
| `WorldGuardRegionProvider` | Query region ownership, members, bounds |
| `WorldGuardRegionProvisioner` | Stamp WG flags on import (BUILD, CHEST_ACCESS, PISTONS, etc.) |
| `WorldGuardRegionMemberSync` | Sync region members from stall data |

## Economy adapter (`infrastructure/vault/`)

| Class | Purpose |
|-------|---------|
| `VaultEconomyProvider` | Direct Vault economy calls |
| `LazyEconomyProvider` | Defers Vault lookup to runtime (economy plugin may load late) |

## LumaGuilds adapter (`infrastructure/lumaguilds/`)

| Class | Purpose |
|-------|---------|
| `LumaGuildsGuildProvider` | Guild membership, rank permissions, bank operations |

## Commands (`infrastructure/commands/`)

Nexus Paper annotated commands:

| Class | Base | Subcommands |
|-------|------|-------------|
| `AdminCommands` | `/em` | limit, import, reload, list, evict, auction …, stall …, rg resync, guild policy, rent resync |
| `ShopCommands` | `/shop` | list, trust, untrust, edit, delete, breakdelete, search, history, admin … |
| `ShopHelpCommands` | `/shophelp` | show |
| `StoreCommands` | `/store` | show |
| `VaultCommands` | `/shopvault` | open |

## Listeners (`infrastructure/listeners/`)

| Listener | Events | Purpose |
|----------|--------|---------|
| `SignPlaceListener` | SignChangeEvent | Shop creation via wall sign placement |
| `ContainerStockListener` | PostShopTransactionEvent, timer | Stock sign updates, depletion tracking |
| `HopperControlListener` | InventoryMoveItemEvent | Hopper access control per-shop |
| `PurchaseSignClickListener` | PlayerInteractEvent | Stall buyout, rent extension |
| `PurchaseSignCreateListener` | SignChangeEvent | Admin stall rent sign registration via `[em]` trigger token |
| `PurchaseSignBreakListener` | BlockBreakEvent | Purchase sign destruction tracking |
| `BlockProtectionListener` | BlockBreakEvent, BlockPlaceEvent | Admin bypass for sign break/place |
| `ShopCreateListener` | ShopCreatedEvent | Shop creation side effects |
| `GuildDisbandedEventListener` | GuildDisbandedEvent | Guild dissolution cleanup |
| `EntityLimitListener` | EntitySpawnEvent | Enforce per-stall entity caps |

## GUI menus (`infrastructure/interaction/gui/`)

IFramework-based inventory GUIs:

| Class | Purpose |
|-------|---------|
| `CreateShopMenu` | Direction, amount, price, cost-type picker |
| `PurchaseMenu` | Shop trade display with owner/stock/direction |
| `OwnedShopsMenu` | Player's shop listing with status |
| `ShopEditMenu` | Edit amount, price, search, frozen |
| `SearchResultsMenu` | `/shop search` results with direction/stock |
| `DeleteShopsMenu` | Pick shops to delete |
| `BulkTrustMenu` | Pick shops to trust a player on |
| `ShopVaultMenu` | Barter vault item withdrawal |
| `AuctionBrowserMenu` | Browse and bid on auctions |
| `GuildPolicyMenu` | Guild tariff/embargo management |

## Bedrock forms (`interaction/bedrock/`)

Cumulus form-based alternatives for Bedrock players:

| Class | Java GUI Equivalent |
|-------|-------------------|
| `BedrockCreateShopForm` | `CreateShopMenu` |
| `BedrockPurchaseForm` | `PurchaseMenu` |
| `BedrockShopEditForm` | `ShopEditMenu` |

## DI wiring (`EnthusiaMarket.kt`)

The plugin entry point:

1. Creates `NexusContext`
2. Registers `DataSource` bean
3. Builds repository chain (SQL → Indexed → decorator)
4. Registers provider adapters
5. Registers commands with `registerPaperCommands`
6. Registers listeners
7. Starts schedulers (rent collection, auction settlement, sign refresh, shop audit)
8. Registers permissions dynamically
