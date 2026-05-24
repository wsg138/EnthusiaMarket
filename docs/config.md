# Config — EnthusiaMarket

**Date:** 2026-05-24
**Status:** Spec (canonical; `src/main/resources/config.yml` extends to match as features land)
**Owner:** BadgersMC

Defines every config key the plugin reads, its type, default, source REQ, and which component consumes it. `INFRA-03` (M0) extends the shipped `config.yml` to cover all keys below.

## Conventions

- All durations are ISO-8601 strings (`PT5M`, `P1D`) unless the key suffix names a unit (`*-sec`, `*-ticks`).
- Money values are integer minor units in the Vault economy (typically whole-coin).
- Percentages are decimals (`0.05` = 5%).
- Booleans use lowercase `true`/`false`.

## 1. `market` — region binding

| Key | Type | Default | REQ | Used by |
|---|---|---|---|---|
| `market.world` | string | `world` | REQ-002 | `EnthusiaMarket.onEnable`, `ImportStallsService` |
| `market.region-prefix` | string | `stall_` | REQ-002 | `ImportStallsService` |

## 2. `rent` — periodic charge

| Key | Type | Default | REQ | Used by |
|---|---|---|---|---|
| `rent.mode` | enum `formula\|flat` | `formula` | REQ-003 | `RentTerms` factory |
| `rent.formula-pct` | decimal | `0.01` (1% of winning-bid per period) | REQ-003 | `RentTerms.formula(pct)` |
| `rent.flat-amount` | integer | `0` | REQ-003 | `RentTerms.flat(amount)` |
| `rent.collection-interval` | duration | `P1D` | REQ-003 | `RentCollectionService` scheduler |
| `rent.grace-period` | duration | `P3D` | REQ-004 | default/eviction in `RentCollectionService` |

## 3. `auction` — timed sales

| Key | Type | Default | REQ | Used by |
|---|---|---|---|---|
| `auction.default-duration` | duration | `PT24H` | REQ-007 | `AuctionLifecycleService.start` |
| `auction.min-duration` | duration | `PT15M` | REQ-007 | validation |
| `auction.max-duration` | duration | `P7D` | REQ-007 | validation |
| `auction.anti-snipe-sec` | integer (seconds) | `30` | REQ-008 | `Auction.antiSnipeWindow` |
| `auction.fee-pct` | decimal | `0.05` (5% to system) | REQ-009 | settlement payout calc |
| `auction.min-starting-bid` | integer | `1` | REQ-007 | validation |

## 4. `shop` — sign trading

| Key | Type | Default | REQ | Used by |
|---|---|---|---|---|
| `shop.tax-pct` | decimal | `0.02` (2% to system) | REQ-006 | `ShopTradeService` |
| `shop.allow-bedrock-edit` | bool | `true` | REQ-011 | sign-edit form trigger |

## 5. `lumaguilds` — guild integration

| Key | Type | Default | REQ | Used by |
|---|---|---|---|---|
| `lumaguilds.enabled` | bool | `true` | REQ-010 | DI module wiring |
| `lumaguilds.manage-rank` | string (rank id) | `officer` | REQ-010 | `Stall.canManage(actor)` authorization |
| `lumaguilds.pay-from` | enum `bank\|leader` | `bank` | REQ-003 | rent debit source for guild stalls |

## 6. `database`

| Key | Type | Default | REQ | Used by |
|---|---|---|---|---|
| `database.type` | enum `sqlite\|mariadb` | `sqlite` | REQ-020 | `Database.open` |
| `database.sqlite-file` | path (relative to data folder) | `enthusiamarket.db` | REQ-020 | sqlite branch |
| `database.mariadb.host` | string | `localhost` | REQ-020 | mariadb branch |
| `database.mariadb.port` | integer | `3306` | REQ-020 | mariadb branch |
| `database.mariadb.database` | string | `enthusiamarket` | REQ-020 | mariadb branch |
| `database.mariadb.username` | string | `em` | REQ-020 | mariadb branch |
| `database.mariadb.password` | string | `""` | REQ-020 | mariadb branch |
| `database.pool.max-size` | integer | `10` | REQ-020 | Hikari config (not yet wired) |

## 7. `bedrock` — Floodgate

| Key | Type | Default | REQ | Used by |
|---|---|---|---|---|
| `bedrock.force-forms` | bool | `false` | REQ-011 | force Cumulus even when Floodgate absent (testing) |
| `bedrock.form-timeout-sec` | integer | `60` | REQ-011 | Cumulus form expiry |

## 8. `debug`

| Key | Type | Default | Used by |
|---|---|---|---|
| `debug.log-economy` | bool | `false` | `VaultEconomyProvider` |
| `debug.log-migrations` | bool | `true` | `Migrations.runAll` |

## Validation rules

- `rent.mode == flat` ⇒ `rent.flat-amount > 0`
- `rent.mode == formula` ⇒ `rent.formula-pct >= 0`
- `auction.min-duration <= auction.default-duration <= auction.max-duration`
- `0 <= shop.tax-pct + auction.fee-pct <= 1`
- `lumaguilds.enabled == false` ⇒ guild-owned stalls cannot be created (REQ-010 inactive)

Invalid config ⇒ disable plugin + log explicit error (parallels REQ-041).
