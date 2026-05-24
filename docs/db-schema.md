# Database Schema — EnthusiaMarket

**Date:** 2026-05-24
**Status:** Living reference; mirrors `src/main/resources/migrations/`
**Owner:** BadgersMC

Authoritative schema definitions live in versioned migration files. This doc summarises shape, relationships, and intent. When you change a migration, update this doc in the same commit.

## Migration order

| Version | File | Adds |
|---|---|---|
| V001 | `V001__init.sql` | All tables below (initial schema) |
| V002 | (planned, TDD-11) | (no new tables — formalises shop sign columns + extra indexes) |
| V003 | (planned, TDD-31) | Indexes for active-auction lookups |

`Migrations.runAll(ds)` applies in numeric order and records `schema_version`; existing versions are skipped (REQ-042).

## Tables

### `stalls` — root aggregate

Owner per stall: either NONE (vacant), PLAYER (uuid), or GUILD (guild id). `region_id` + `world` is the natural key bound to a WorldGuard region.

| Column | Type | Notes |
|---|---|---|
| `id` | TEXT PK | `StallId` (UUID string) |
| `region_id` | TEXT | WG region name |
| `world` | TEXT | Bukkit world name |
| `state` | TEXT | `VACANT`, `RENTED`, `OWNED`, `DEFAULTED` |
| `owner_type` | TEXT | `NONE`, `PLAYER`, `GUILD` |
| `owner_id` | TEXT | UUID or guild id; empty for NONE |
| `owner_since` | INTEGER | epoch millis; null when vacant |
| `winning_bid` | INTEGER | last awarded bid; basis for `formula` rent |
| `rent_mode` | TEXT | `FORMULA`, `FLAT` |
| `rent_pct` | REAL | when `FORMULA` |
| `rent_flat` | INTEGER | when `FLAT` |

Unique: `(world, region_id)`. Index: `state`.

### `auctions` — timed sale lots

A stall hosts at most one OPEN auction at a time. Anti-snipe: when a winning bid lands within `anti_snipe_sec` of `end_at`, `end_at` shifts forward by that window.

| Column | Type | Notes |
|---|---|---|
| `id` | TEXT PK | `AuctionId` |
| `stall_id` | TEXT FK → stalls | |
| `state` | TEXT | `OPEN`, `CLOSED`, `SETTLED`, `CANCELLED` |
| `start_at` | INTEGER | epoch millis |
| `end_at` | INTEGER | epoch millis; mutable by anti-snipe |
| `starting_bid` | INTEGER | |
| `high_bid_amount` | INTEGER NULL | |
| `high_bidder` | TEXT NULL | uuid |
| `high_placed_at` | INTEGER NULL | epoch millis |
| `anti_snipe_sec` | INTEGER | snapshot of `auction.anti-snipe-sec` at start |

Index: `(stall_id, state)`.

### `bids` — append-only bid log

| Column | Type | Notes |
|---|---|---|
| `id` | INTEGER PK auto | |
| `auction_id` | TEXT FK | |
| `bidder_uuid` | TEXT | |
| `amount` | INTEGER | |
| `placed_at` | INTEGER | epoch millis |

### `signs` — shop signs

Sign tied to a stall, holds buy/sell direction, item key, price, and linked container. `sign_location` and `container_loc` are world+coord strings (e.g. `world,123,64,-789`).

| Column | Type | Notes |
|---|---|---|
| `id` | INTEGER PK auto | |
| `stall_id` | TEXT FK | |
| `direction` | TEXT | `BUY` (player buys from stall) / `SELL` (player sells to stall) |
| `item_key` | TEXT | serialized item descriptor (NBT base64 or item key) |
| `price` | INTEGER | per-trade amount |
| `sign_location` | TEXT UNIQUE | |
| `container_loc` | TEXT | |

### `sales_ledger` — per-trade record

| Column | Type | Notes |
|---|---|---|
| `id` | INTEGER PK auto | |
| `stall_id` | TEXT FK | |
| `sign_id` | INTEGER FK | |
| `buyer_uuid` | TEXT | |
| `gross` | INTEGER | price paid by buyer |
| `tax` | INTEGER | `shop.tax-pct` portion to system |
| `net` | INTEGER | gross − tax, credited to owner |
| `occurred_at` | INTEGER | epoch millis |

Index: `(stall_id, occurred_at)`.

### `rent_ledger` — billing record

One row per rent collection attempt, paid or not. `paid = 0` flags a defaulted period.

| Column | Type | Notes |
|---|---|---|
| `id` | INTEGER PK auto | |
| `stall_id` | TEXT FK | |
| `charged_at` | INTEGER | epoch millis |
| `amount` | INTEGER | computed by `RentTerms` |
| `paid` | INTEGER | 0 or 1 |
| `payer_type` | TEXT | `PLAYER` / `GUILD` |
| `payer_id` | TEXT | uuid or guild id |

Index: `(stall_id, charged_at)`.

### `grace_events` — default-grace tracking

Opens when rent fails; `expires_at = started_at + rent.grace-period`. `cured_at` set when owner tops up before expiry. Expiry without cure ⇒ eviction.

| Column | Type | Notes |
|---|---|---|
| `id` | INTEGER PK auto | |
| `stall_id` | TEXT FK | |
| `started_at` | INTEGER | |
| `expires_at` | INTEGER | |
| `cured_at` | INTEGER NULL | |

### `favorites` — player bookmark

| Column | Type | Notes |
|---|---|---|
| `player_uuid` | TEXT | composite PK part |
| `stall_id` | TEXT FK | composite PK part |
| `favorited_at` | INTEGER | epoch millis |

### `schema_version` — migration tracker

| Column | Type | Notes |
|---|---|---|
| `version` | INTEGER PK | matches `V###` |
| `applied_at` | INTEGER | epoch millis |

## Cross-cutting notes

- All times are epoch millis (UTC). Domain layer converts to `java.time.Instant`.
- All money is integer minor units (matches Vault `double` after rounding); ledgers preserve exact integer arithmetic.
- SQLite path (default) uses single-file DB at `plugins/EnthusiaMarket/<sqlite-file>`; MariaDB path uses pooled Hikari over JDBC.
- Foreign keys on SQLite require `PRAGMA foreign_keys = ON` — see `Database.open` (verify before relying on cascade behavior).
