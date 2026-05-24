# Permissions — EnthusiaMarket

**Date:** 2026-05-24
**Status:** Spec (canonical; `plugin.yml` `permissions:` block extends to match as commands land)
**Owner:** BadgersMC

Permission node taxonomy. All nodes are namespaced under `enthusiamarket.*` with `em.*` as alias. Nodes marked **(default: op)** require LuckPerms / operator status by default; all others default to `true` (granted).

## Top-level group

| Node | Default | Description |
|---|---|---|
| `enthusiamarket.*` | op | Wildcard — grants every node below |
| `enthusiamarket.admin` | op | Wildcard for all admin nodes |
| `enthusiamarket.player` | true | Wildcard for all player-facing nodes |

## Admin (REQ-022)

| Node | Default | Command / use |
|---|---|---|
| `enthusiamarket.admin.import` | op | `/em import` (REQ-002) |
| `enthusiamarket.admin.list` | op | `/em list` |
| `enthusiamarket.admin.evict` | op | `/em evict <stall>` — manual eviction overriding grace |
| `enthusiamarket.admin.reload` | op | `/em reload` config + repository caches |
| `enthusiamarket.admin.setowner` | op | `/em setowner <stall> <player\|guild:id>` |
| `enthusiamarket.admin.refund` | op | reverse a sale/auction settlement from ledger |

## Stalls — player & guild (REQ-001, REQ-010)

| Node | Default | Use |
|---|---|---|
| `enthusiamarket.stall.rent` | true | Take a vacant stall on rent (`/em stall rent <id>`) |
| `enthusiamarket.stall.bid` | true | Bid in an ownership auction (separate from item auctions) |
| `enthusiamarket.stall.manage.own` | true | Manage stall the actor owns (signs, rent payment) |
| `enthusiamarket.stall.manage.guild` | true | Gated additionally by `lumaguilds.manage-rank` check; the perm is the user-side gate, the rank check is the guild-side gate (REQ-010) |
| `enthusiamarket.stall.favorite` | true | Add/remove favorites |

## Shop signs (REQ-005, REQ-006)

| Node | Default | Use |
|---|---|---|
| `enthusiamarket.shop.create` | true | Place a recognised shop sign in own/guild stall |
| `enthusiamarket.shop.break` | true | Break own shop sign (or admin) |
| `enthusiamarket.shop.buy` | true | Interact with BUY signs as customer |
| `enthusiamarket.shop.sell` | true | Interact with SELL signs as supplier |

## Auctions (REQ-007 – REQ-009)

| Node | Default | Use |
|---|---|---|
| `enthusiamarket.auction.start` | true | `/em auction start <duration> <starting-bid>` |
| `enthusiamarket.auction.bid` | true | `/em bid <auction-id> <amount>` |
| `enthusiamarket.auction.cancel` | true | Cancel own auction before first bid |
| `enthusiamarket.auction.cancel.force` | op | Cancel any auction (admin) |
| `enthusiamarket.auction.list` | true | List open auctions |

## Bedrock UI (REQ-011)

| Node | Default | Use |
|---|---|---|
| `enthusiamarket.bedrock.form` | true | Allowed to receive Cumulus forms (revoke to fall back to Bukkit GUI) |

## Inheritance map (for LuckPerms group setup)

```
enthusiamarket.* (op)
├── enthusiamarket.admin (op)
│   └── enthusiamarket.admin.* — every admin.* node
└── enthusiamarket.player (true)
    ├── enthusiamarket.stall.* (except admin overrides)
    ├── enthusiamarket.shop.*
    ├── enthusiamarket.auction.* (except .cancel.force)
    └── enthusiamarket.bedrock.form
```

## plugin.yml example block

```yaml
permissions:
  enthusiamarket.*:
    default: op
    children:
      enthusiamarket.admin: true
      enthusiamarket.player: true
  enthusiamarket.admin:
    default: op
    children:
      enthusiamarket.admin.import: true
      enthusiamarket.admin.list: true
      enthusiamarket.admin.evict: true
      enthusiamarket.admin.reload: true
      enthusiamarket.admin.setowner: true
      enthusiamarket.admin.refund: true
      enthusiamarket.auction.cancel.force: true
  enthusiamarket.player:
    default: true
    children:
      enthusiamarket.stall.rent: true
      enthusiamarket.stall.bid: true
      enthusiamarket.stall.manage.own: true
      enthusiamarket.stall.manage.guild: true
      enthusiamarket.stall.favorite: true
      enthusiamarket.shop.create: true
      enthusiamarket.shop.break: true
      enthusiamarket.shop.buy: true
      enthusiamarket.shop.sell: true
      enthusiamarket.auction.start: true
      enthusiamarket.auction.bid: true
      enthusiamarket.auction.cancel: true
      enthusiamarket.auction.list: true
      enthusiamarket.bedrock.form: true
```
