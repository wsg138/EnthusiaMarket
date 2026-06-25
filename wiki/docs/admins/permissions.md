---
title: Permissions
audience: admin
topic: permissions
summary: All permission nodes used by EnthusiaMarket, grouped by feature area.
keywords: [permissions, nodes, admin, user]
related: [installation, config]
updated: 2026-06-25
---

# Permissions

All permissions default to **OP** unless noted otherwise. Permissions are registered dynamically at runtime.

## Player permissions

### Shop

| Node | Description |
|------|-------------|
| `enthusiamarket.shop.use` | Use `/shop` commands: list, trust, untrust, edit, delete, breakdelete, search, history |
| `enthusiamarket.shop.create` | Place shop signs (`[BUY]`/`[SELL]`/`[TRADE]`) |
| `enthusiamarket.shop.delete.all` | Use `/shop delete all` (bulk delete) |
| `enthusiamarket.shop.help` | Use `/shophelp show` |
| `enthusiamarket.shop.store` | Use `/store show` |
| `enthusiamarket.shop.vault` | Open shop vault with `/shopvault open` |

### Stall

| Node | Description |
|------|-------------|
| `enthusiamarket.stall.info` | View stall info and limits (`/em stall info`, `/em limit`) |
| `enthusiamarket.stall.members` | Manage stall members |
| `enthusiamarket.stall.offer` | Create and cancel sell offers |
| `enthusiamarket.stall.buy` | Buy stalls via sell offers |
| `enthusiamarket.stall.sellback` | Voluntary stall relinquish for refund |
| `enthusiamarket.stall.buyout` | Buy UNOWNED stalls from purchase signs |
| `enthusiamarket.stall.setkind` | Set stall region kind |
| `enthusiamarket.stall.entitylimit` | Set per-stall entity limits |
| `enthusiamarket.stall.recount` | Recount entities in stall region |
| `enthusiamarket.stall.outline` | Render stall boundary particles |

### Auction

| Node | Description |
|------|-------------|
| `enthusiamarket.auction.bid` | Place bids (`/em bid`) |
| `enthusiamarket.auction.list` | Browse auctions (`/em auctions`) |

### Sign

| Node | Description |
|------|-------------|
| `enthusiamarket.sign.create` | Place purchase signs (`[em]` token) |
| `enthusiamarket.sign.admin` | Overwrite existing purchase signs |

### Guild

| Node | Description |
|------|-------------|
| `enthusiamarket.guild.policy` | Manage guild trade policies (`/em guild policy`) |

## Admin permissions

| Node | Description |
|------|-------------|
| `enthusiamarket.admin` | Core admin: auction management, rg resync, bypass checks |
| `enthusiamarket.admin.shop` | Admin shop management: view, info, remove, fix, breakothers, vault, contents |
| `enthusiamarket.admin.import` | Import WorldGuard regions as stalls (`/em import`) |
| `enthusiamarket.admin.reload` | Reload config and lang files (`/em reload`, `/em rent resync`) |
| `enthusiamarket.admin.list` | List all stalls (`/em list`) |
| `enthusiamarket.admin.evict` | Force-evict a stall (`/em evict`) |

## Limit groups

Permission nodes matching `enthusiamarket.limit.<group-name>` grant membership in a named limit group from config. Effective limits merge by taking the best value per dimension across all granted groups.

Example:

```text
enthusiamarket.limit.vip      → total: 3 stalls
enthusiamarket.limit.premium  → total: 10 stalls
```

Players with no limit group get `defaultStallLimit` (config, default -1 = unlimited).

## PlaceholderAPI

| Placeholder | Description |
|-------------|-------------|
| `%enthusiamarket_shops_total%` | Total shops in database |
| `%enthusiamarket_shops_owned%` | Shops owned by the player |
| `%enthusiamarket_sales_unseen%` | Un-notified sales for the player |
