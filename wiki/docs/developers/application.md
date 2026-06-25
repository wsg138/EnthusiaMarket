---
title: Application layer
audience: dev
topic: application
summary: Application services and use cases — what happens between domain and infrastructure.
keywords: [application, services, use-cases, workflow]
related: [architecture, domain, infrastructure]
updated: 2026-06-25
---

# Application layer

The application layer (`application/`) implements use cases. It depends on domain ports only — no framework code.

## Core services

### Stall lifecycle

| Service | Responsibility |
|---------|---------------|
| `ImportStallsService` | Scan WG regions, register new stalls, provision flags |
| `StallBuyoutService` | Process UNOWNED stall purchase from sign |
| `StallRentExtensionService` | Handle rent pre-payment (double-click sign) |
| `StallSellbackService` | Voluntary relinquish with prorated refund |
| `RentCollectionService` | Periodic rent deduction, grace transition, eviction |
| `AuctionLifecycleService` | Auction open, bid placement, settlement |

### Shop operations

| Service | Responsibility |
|---------|---------------|
| `ShopFactory` | Build Shop instances from GUI/user input |
| `ContainerTradeService` | BUY/SELL/TRADE transaction processing |
| `ShopManagementService` | Shop CRUD, trust/untrust, search queries |
| `LimitResolutionService` | Effective stall limits from permission groups |

### Performance

| Component | Responsibility |
|-----------|---------------|
| `IndexedShopRepository` | Decorator: intercept all `ShopRepository` mutations, sync in-memory index |
| `InMemoryShopLocationIndex` | O(1) container→shop lookup, replaces DB query on hopper hot path |
| `ShopLocationIndex` | Domain port for the index |
| `MaterialSuggestions` | Pure case-insensitive prefix filter for `/shop search` tab-completion |

## Design patterns

### Repository decorator

`IndexedShopRepository` wraps any `ShopRepository` delegate:

```text
consumers → ShopRepository (interface)
                ↓
         IndexedShopRepository (decorator, application layer)
                ↓
         ShopRepositorySql (delegate, infrastructure layer)
```

Every mutation (upsert, delete, etc.) updates both the DB and the in-memory index. `findByContainer` serves directly from the index — zero DB queries on the hopper server thread.

### Constructor injection

All services receive their dependencies via constructor parameters. DI wiring happens in `EnthusiaMarket.onEnable`:

```kotlin
val shopSqlRepo = ShopRepositorySql(dataSource)
val index = InMemoryShopLocationIndex()
val indexedRepo = IndexedShopRepository(shopSqlRepo, index)
ctx.registerBean<ShopRepository>(indexedRepo)
```

### Result types

Services return sealed `Result` classes (not exceptions) for predictable control flow:

```kotlin
sealed class Result<out T> {
    data class Ok<T>(val value: T) : Result<T>()
    data class Rejected(val reason: String) : Result<Nothing>()
}
```
