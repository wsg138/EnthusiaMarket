---
title: Config reference
audience: admin
topic: config
summary: Every config key in enthusiamarket.yaml with defaults and descriptions.
keywords: [config, reference, yaml, settings]
related: [installation, permissions, integration]
updated: 2026-06-25
---

# Config reference

Config file: `plugins/EnthusiaMarket/enthusiamarket.yaml`

## Market

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `market.world` | String | `"world"` | World name where stall regions exist. |
| `market.regionPrefix` | String | `"stall"` | WorldGuard region prefix for stall detection. |
| `market.stallPriority` | Int | `20` | WG priority for stall regions. Must exceed safezone. |

## Rent

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `rent.mode` | String | `"formula"` | `formula` (percentage) or `flat` (fixed). |
| `rent.formulaPct` | Double | `1.0` | Percentage of winning bid per period. 1.0 = 1%. |
| `rent.flatAmount` | Long | `0` | Flat rent per period when mode=flat. |
| `rent.collectionInterval` | String | `"P1D"` | ISO-8601 duration between collections. |
| `rent.gracePeriod` | String | `"P3D"` | Grace before emergency auction after missed rent. |
| `rent.maxPrepaidPeriods` | Int | `0` | Max periods a stall can be pre-paid (0 = unlimited). |

## Auction

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `auction.defaultDuration` | String | `"PT24H"` | Default auction duration. |
| `auction.minDuration` | String | `"PT15M"` | Minimum auction duration. |
| `auction.maxDuration` | String | `"P7D"` | Maximum auction duration. |
| `auction.antiSnipeSec` | Int | `30` | Anti-snipe window in seconds. |
| `auction.feePct` | Double | `0.05` | Fee deducted from seller (5%). |
| `auction.minStartingBid` | Long | `1` | Minimum starting bid. |

## Shop

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `shop.taxPct` | Double | `0.02` | Tax on trades (2%). |
| `shop.allowBedrockEdit` | Boolean | `true` | Allow Bedrock sign editing via form. |
| `shop.taxDestination` | String | `"system"` | UUID for tax deposit; `"system"` = sink. |
| `shop.searchDefault` | Boolean | `true` | New shops searchable by default. |
| `shop.notifyEnabled` | Boolean | `true` | Notify owners of trades. |
| `shop.historyRetentionDays` | Int | `30` | Days of history kept (0 = forever). |

## LumaGuilds

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `lumaguilds.enabled` | Boolean | `true` | Enable guild integration. |
| `lumaguilds.payFrom` | String | `"bank"` | Rent source: `bank` or `leader`. |

## Database

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `database.type` | String | `"sqlite"` | `sqlite` or `mariadb`. |
| `database.sqliteFile` | String | `"enthusiamarket.db"` | SQLite filename. |
| `database.mariadb.host` | String | `"localhost"` | MariaDB host. |
| `database.mariadb.port` | Int | `3306` | MariaDB port. |
| `database.mariadb.database` | String | `"enthusiamarket"` | Database name. |
| `database.mariadb.username` | String | `"em"` | Database username. |
| `database.mariadb.password` | String | `""` | Database password. |
| `database.pool.maxSize` | Int | `10` | Connection pool max. |

## Bedrock

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `bedrock.forceForms` | Boolean | `false` | Force forms without Floodgate. |
| `bedrock.formTimeoutSec` | Int | `60` | Form timeout seconds. |

## Lang / Debug

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `lang.locale` | String | `"en_US"` | Locale ID for lang files. |
| `debug.logEconomy` | Boolean | `false` | Log all economy transactions. |
| `debug.logMigrations` | Boolean | `true` | Log migration execution. |

## Signs

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `signs.triggerToken` | String | `"[em]"` | First-line token for purchase signs. |
| `signs.ownerNameTemplate` | String | `"%player_name%"` | SOLO owner name template. |
| `signs.guildNameTemplate` | String | `"%guild_name%"` | GUILD owner name template. |
| `signs.confirmWindowSec` | Int | `10` | Confirm window for rent extension. |

## Schematics

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `schematics.enabled` | Boolean | `true` | Master switch for schematic snapshots. |
| `schematics.directory` | String | `"schematics"` | Directory for `.schem` files. |

## Particles

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `particles.enabled` | Boolean | `true` | Particle boundary outlines. |
| `particles.maxPerTick` | Int | `200` | Max particles per tick. |

## GuildPolicy

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `guildPolicy.announceEnabled` | Boolean | `true` | Broadcast policy changes. |
| `guildPolicy.entryWarningEnabled` | Boolean | `true` | Warn entering tariffed stall. |
| `guildPolicy.announceCooldownSeconds` | Int | `30` | Anti-spam cooldown. |

## ShopAudit

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `shopAudit.enabled` | Boolean | `true` | Periodic shop audit sweeper. |
| `shopAudit.intervalMinutes` | Int | `10` | Minutes between sweeps. |
| `shopAudit.maxPerTick` | Int | `5` | Max shops per tick. |
| `shopAudit.repairEnabled` | Boolean | `true` | Auto-delete broken shops. |

## Limits

```yaml
limits:
  vip:
    total: 3               # Max stalls for this group
    regionkinds: {}         # Per-kind caps
  premium:
    total: 10

defaultStallLimit: -1       # Default when no group matches (-1 = unlimited)
```

Each limit group maps to the permission node `enthusiamarket.limit.<group-name>`.

## Store

| Key | Type | Default | Description |
|-----|------|---------|-------------|
| `store.url` | String | `"https://example.com/store"` | URL for `/store` command. |

## External: entitylimits.yml

Path: `plugins/EnthusiaMarket/entitylimits.yml`. Controls entity caps per region kind. Never overwrites local edits.

```yaml
default:
  _total: 50
  villager: 5
  armor_stand: 10
  item_frame: 10

shop:
  _total: 80
  villager: 10
  armor_stand: 20
  item_frame: 20

farm:
  _total: 120
  villager: 20
