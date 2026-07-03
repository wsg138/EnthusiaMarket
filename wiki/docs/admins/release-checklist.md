---
title: Release Checklist
audience: admin
topic: release-checklist
summary: Step-by-step deployment checklist for EnthusiaMarket — permissions, config, and verification.
keywords: [release, checklist, deployment, admin, permissions, config, setup]
related: [installation, permissions, config, troubleshooting]
updated: 2026-06-30
---

# Release Checklist

Step-by-step deployment guide for admins releasing EnthusiaMarket to production.

## 1. Prerequisites (Dependencies)

| Dependency | Required? | Notes |
|---|---|---|
| Paper 1.21.11+ | **Hard** | `/version` to verify |
| WorldGuard 7.x | **Hard** | Stall region management |
| Vault | **Hard** | Economy abstraction |
| EnthusiaCurrency | **Hard** | Actual economy provider |
| LumaGuilds | **Hard** | Guild integration |
| PlaceholderAPI | Soft | `%enthusiamarket_*%` placeholders |
| WorldEdit / FAWE | Soft | Schematic snapshots |
| Geyser + Floodgate | Soft | Bedrock forms |

## 2. Permissions to Grant

### Admin team

Grant these to staff rank:

```text
enthusiamarket.admin              — auction management, /em rg resync, bypass checks
enthusiamarket.admin.shop         — shop admin: view, info, remove, fix, breakothers, vault, contents
enthusiamarket.admin.import       — /em import
enthusiamarket.admin.reload       — /em reload, /em rent resync
enthusiamarket.admin.list         — /em list
enthusiamarket.admin.evict        — /em evict
```

### Players

Grant these to default rank. Nodes default to OP in plugin.yml, but grant explicitly if OP is disabled:

```text
enthusiamarket.shop.help          — /shophelp
enthusiamarket.shop.use           — /shop list, trust, untrust, edit, delete, search, history
enthusiamarket.shop.create        — place shop signs ([BUY]/[SELL]/[TRADE])
enthusiamarket.shop.delete.all    — /shop delete all (bulk delete)
enthusiamarket.shop.store         — /store
enthusiamarket.shop.vault         — /shopvault open
enthusiamarket.stall.info         — /em limit, /em stall info
enthusiamarket.stall.members      — /em stall members add/remove/list
enthusiamarket.stall.offer        — /em stall offer, /em stall offer cancel
enthusiamarket.stall.buy          — /em stall buy
enthusiamarket.stall.sellback     — /em sellback, /em sellback confirm
enthusiamarket.stall.buyout       — purchase-sign buyout
enthusiamarket.stall.setkind      — /em stall setkind
enthusiamarket.stall.entitylimit  — /em stall entitylimit set
enthusiamarket.stall.recount      — /em stall recount
enthusiamarket.stall.outline      — /em stall outline
enthusiamarket.auction.bid        — /em bid
enthusiamarket.auction.list       — /em auctions
enthusiamarket.sign.create        — place [em] purchase signs
enthusiamarket.sign.admin         — overwrite existing purchase signs
enthusiamarket.guild.policy       — /em guild policy
```

### Limit groups (optional)

Tiered stall caps via permission nodes matching `enthusiamarket.limit.<group-name>`:

```text
enthusiamarket.limit.vip          — uses limits.vip from config
enthusiamarket.limit.premium      — uses limits.premium from config
```

Players with no limit group get `defaultStallLimit` from config (default: -1 = unlimited).

## 3. Deployment Steps

### Step 1 — Drop the JAR

Stop server, drop `EnthusiaMarket.jar` into `plugins/`, start server.
For hot-deploy: drop JAR, then `/em reload`.

### Step 2 — Verify it loaded

```text
/version EnthusiaMarket
```

Check console for startup errors. All 5 hard dependencies (Paper + 4 plugins) must be present.

### Step 3 — Configure market world and region prefix

Edit `plugins/EnthusiaMarket/enthusiamarket.yaml`:

```yaml
market:
  world: "world"           # your market world name
  regionPrefix: "stall"    # WG region prefix (e.g. stall1, stall2, ...)
  stallPriority: 20        # must exceed surrounding safezone priority
```

### Step 4 — Create WorldGuard regions

Create WG regions matching `market.regionPrefix` + number:

```text
/rg create stall1
/rg create stall2
...
```

Each region is one stall.

### Step 5 — Import stalls

```text
/em import
```

Registers all WG regions matching the prefix as stalls. Output shows created, skipped, and provisioned counts.

### Step 6 — Configure rent

Edit `enthusiamarket.yaml`:

```yaml
rent:
  mode: flat                 # "flat" or "formula"
  flatAmount: 500            # per-period cost
  collectionInterval: "P1D"  # ISO-8601 duration: P1D = daily, P7D = weekly
  gracePeriod: "P3D"         # grace period before emergency auction
  maxPrepaidPeriods: 0       # max pre-paid periods (0 = unlimited)
```

> **⚠️ `rent.formulaPct` uses "human percent" scale.** `1.0` = 1% of the winning bid per period, NOT 0.01. Setting `100.0` = 100% of the winning bid per period.

Push terms to existing stalls:

```text
/em rent resync
```

### Step 7 — Verify stall import

```text
/em list
```

All stalls should show as `UNOWNED`.

### Step 8 — Grant player permissions

Use your permission plugin (LuckPerms, etc.) to grant the player permission set from [Section 2](#2-permissions-to-grant).

### Step 9 — Verify Bedrock (if Geyser present)

- `bedrock.forceForms: false` (default — auto-detect Floodgate)
- `shop.allowBedrockEdit: true` (default)
- Test: Bedrock player edits a shop sign → should get Cumulus form

### Step 10 — Final verification

Quick sanity checks:

```text
/em help                    → help menu opens
/em stall info stall1       → stall info card shows
/em auctions                → auction browse menu opens (even if empty)
```

Player-side:

- Place a `[BUY]` / `[SELL]` / `[TRADE]` shop sign → shop creates
- Place an `[em]` purchase sign → buyout GUI opens
- `/shop list` → lists player's shops

## 4. Admin Commands Cheat Sheet

| Command | What it does |
|---|---|
| `/em import` | Register WG regions as stalls |
| `/em reload` | Reload config and language files |
| `/em list` | List all stalls |
| `/em stall info <id>` | Full stall details card |
| `/em stall setkind <id> <kind>` | Set region kind (`shop`, `farm`, etc.) |
| `/em stall entitylimit set <id> <type> <extra>` | Per-stall entity cap override |
| `/em stall recount <id>` | Recount entities in stall region |
| `/em stall outline <id> <seconds>` | Particle boundary outline for N seconds |
| `/em rg resync` | Rebuild WG ACLs from database (fix desync) |
| `/em rent resync` | Push config rent terms to all existing stalls |
| `/em auction start <stall> <price> [duration]` | Start auction for a stall |
| `/em auction startall <price> [duration]` | Mass auction all unowned stalls |
| `/em auction cancel <id>` | Cancel an auction |
| `/em evict <stall>` | Force-evict stall owner |
| `/shop` | Player shop management |
| `/shophelp` | Shop tutorial |
| `/shopvault open` | Withdraw barter payments |
| `/store` | Server store link |

## 5. Common Pitfalls

- **No stalls after import**: `market.world` must match the actual world name. WG regions must use the configured prefix.
- **Rent collecting too fast**: `rent.formulaPct` is a percentage, not a fraction. `1.0` = 1% per period, not 100%. Use `flat` mode for predictable costs.
- **`/em reload` doesn't push rent changes**: Always run `/em rent resync` after changing rent config.
- **Bedrock forms not opening**: Floodgate must be loaded. Check console for `[floodgate]` on plugin list.
- **Shops not working**: Player must own the stall (or be a member). For guild stalls, player needs `MANAGE_SHOPS` guild permission.
- **Economy disabled**: EnthusiaCurrency must be loaded. Check `/plugins` output.
- **Plugin won't enable**: All 5 hard dependencies (Paper 1.21.11+, WorldGuard, Vault, EnthusiaCurrency, LumaGuilds) must load BEFORE EnthusiaMarket.
