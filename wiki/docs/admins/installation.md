---
title: Installation & Configuration
audience: admin
topic: installation
summary: How to install and configure EnthusiaMarket on your server.
keywords: [installation, configuration, setup, config]
related: []
updated: 2026-06-06
---

# Installation & Configuration

## Requirements

- **Server**: Paper 1.21+ (or compatible fork).
- **Dependencies**: Vault, WorldGuard, WorldEdit.
- **Optional**: LumaGuilds (guild-owned stalls), PlaceholderAPI (sign templates), Floodgate (Bedrock support).

## Installation

1. Download the latest `EnthusiaMarket.jar` from the [releases page](https://github.com/BadgersMC/EnthusiaMarket/releases).
2. Place it in your server's `plugins/` folder.
3. Start the server to generate config files.
4. Edit `plugins/EnthusiaMarket/enthusiamarket.yml` (see below).
5. Run `/em reload` or restart.

## Configuration

The main config file is `plugins/EnthusiaMarket/enthusiamarket.yml`.

### Market

```yaml
market:
  world: "world"           # World where stall regions exist
  regionPrefix: "stall"    # WorldGuard region prefix (e.g., stall1, stall2)
  stallPriority: 20        # WG priority (must exceed safezone)
```

### Rent

```yaml
rent:
  mode: "formula"          # "formula" or "flat"
  formulaPct: 1.0          # % of winning bid per day (formula mode)
  flatAmount: 0            # Fixed rent per day (flat mode)
  collectionInterval: "P1D" # ISO-8601 duration between collections
  gracePeriod: "P3D"       # ISO-8601 grace period before eviction
```

### Auctions

```yaml
auction:
  defaultDuration: "PT24H"  # Default auction length
  minDuration: "PT15M"      # Minimum auction length
  maxDuration: "P7D"        # Maximum auction length
  antiSnipeSec: 30          # Anti-snipe extension in seconds
  feePct: 0.05              # Fee on stall sales (5%)
  minStartingBid: 1         # Minimum starting bid
```

### Shops

```yaml
shop:
  taxPct: 0.02              # Tax on shop trades (2%)
  allowBedrockEdit: true    # Let Bedrock players edit via forms
  taxDestination: "system"  # Where tax goes (UUID or "system")
  searchDefault: true       # New shops searchable by default
  notifyEnabled: true       # Notify owners of sales
  historyRetentionDays: 30  # Days to keep transaction history
```

### Signs

```yaml
signs:
  triggerToken: "[em]"      # First line to create a purchase sign
  ownerNameTemplate: "%player_name%"
  guildNameTemplate: "%guild_name%"
  confirmWindowSec: 10      # Rent extension confirm window
```

### LumaGuilds integration

```yaml
lumaguilds:
  enabled: true             # Enable guild-owned stalls
  payFrom: "bank"           # "bank" or "leader" for rent payments
```

### Database

```yaml
database:
  type: "sqlite"            # "sqlite" or "mariadb"
  sqliteFile: "enthusiamarket.db"
  mariadb:
    host: "localhost"
    port: 3306
    database: "enthusiamarket"
    username: "em"
    password: ""
```

## Importing stalls

After configuring WorldGuard regions for your market:

```
/em import
```

This scans the configured world for regions matching the prefix and imports them as stalls.

## Ownership limits

Define limit groups in the `limits` section of the config:

```yaml
limits:
  default:
    total: 2
    regionkinds:
      premium: 1
  vip:
    total: 5
    regionkinds:
      premium: 3
```

Assign groups with permissions: `enthusiamarket.limit.default`, `enthusiamarket.limit.vip`.

## Permissions

| Permission | Description |
|-----------|-------------|
| `enthusiamarket.shop.use` | Use `/shop` commands |
| `enthusiamarket.shop.vault` | Access shop vault |
| `enthusiamarket.shop.delete.all` | Delete all shops at once |
| `enthusiamarket.stall.info` | View stall info |
| `enthusiamarket.stall.members` | Manage stall members |
| `enthusiamarket.stall.offer` | Create/cancel sell offers |
| `enthusiamarket.stall.buy` | Buy stalls from sell offers |
| `enthusiamarket.stall.sellback` | Sell stalls back to system |
| `enthusiamarket.auction.list` | Browse auctions |
| `enthusiamarket.admin` | Full admin access |
| `enthusiamarket.admin.shop` | Shop admin commands |
| `enthusiamarket.admin.import` | Import stalls |
| `enthusiamarket.admin.reload` | Reload config |
| `enthusiamarket.admin.list` | List all stalls |
| `enthusiamarket.limit.<group>` | Assign ownership limit group |
