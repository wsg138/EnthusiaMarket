---
title: Domain model
audience: dev
topic: domain
summary: Domain entities, value objects, enums, and repository/provider ports.
keywords: [domain, model, entities, enums, ports, repository, ddd]
related: [architecture, application]
updated: 2026-06-25
---

# Domain model

The domain layer (`domain/`) holds the rules of the game. Zero framework imports.

## Core aggregates

### Stall

The central aggregate. Represents a market stall (WorldGuard region).

```kotlin
data class Stall(
    val id: StallId,
    val state: StallState,
    val owner: OwnerRef,
    val rentTerms: RentTerms,
    val members: Set<UUID>,
    val guildId: UUID?,
    // ...
)
```

### Shop

A sign-linked trading endpoint on a container.

```kotlin
data class Shop(
    val id: ShopId,
    val ownerId: UUID,
    val guildId: UUID?,
    val containerWorld: String,
    val containerX: Int,
    val containerY: Int,
    val containerZ: Int,
    val signWorld: String,
    val signX: Int,
    val signY: Int,
    val signZ: Int,
    val itemBase64: String,
    val sellAmount: Int,
    val direction: SignDirection,
    val costAmount: Double,
    val costItemBase64: String?,
    val searchEnabled: Boolean,
    val frozen: Boolean,
    val stockCount: Int,
    val hopperAllowIn: Boolean,
    val hopperAllowOut: Boolean
)
```

### Auction

A stall auction with bids.

```kotlin
data class Auction(
    val id: AuctionId,
    val stallId: StallId,
    val state: AuctionState,
    val startingBid: Long,
    val currentBid: Long?,
    val currentBidder: UUID?,
    // ...
)
```

## Key enums

| Enum | Values | Meaning |
|------|--------|---------|
| `StallState` | UNOWNED, AUCTIONING, OWNED, GRACE, RE_AUCTIONING, EMERGENCY_AUCTIONING | Stall lifecycle states |
| `SignDirection` | BUY, SELL, TRADE | Shop economic direction |
| `AuctionState` | OPEN, CLOSED, CANCELLED | Auction lifecycle |
| `OwnerType` | NONE, SOLO, GUILD | Stall ownership model |
| `PolicyKind` | TARIFF, EMBARGO | Guild trade policy type |
| `GuildPermission` | MANAGE_SHOPS, ACCESS_SHOP_CHESTS, EDIT_SHOP_STOCK, MODIFY_SHOP_PRICES | Guild rank permissions |
| `RentTerms.Mode` | FORMULA, FLAT | Rent calculation mode |

## Repository ports (domain interfaces)

| Port | Implementation | Purpose |
|------|---------------|---------|
| `StallRepository` | `StallRepositorySql` | Stall CRUD, lookup by region |
| `ShopRepository` | `ShopRepositorySql` | Shop CRUD, lookup by container |
| `AuctionRepository` | `AuctionRepositorySql` | Auction CRUD, bid tracking |
| `ShopVaultRepository` | `ShopVaultRepositorySql` | Barter payment item storage |

## Provider ports (domain interfaces)

| Port | Implementation | Purpose |
|------|---------------|---------|
| `RegionProvider` | `WorldGuardRegionProvider` | Region membership queries |
| `RegionProvisioner` | `WorldGuardRegionProvisioner` | Region flag provisioning on import |
| `EconomyProvider` | `VaultEconomyProvider` | Economy operations (withdraw/deposit/balance) |
| `GuildProvider` | `LumaGuildsGuildProvider` | Guild membership, permissions, bank |
| `SchematicService` | `WorldEditSchematicAdapter` | Stall schematic snapshot/restore |
