---
title: Guild stalls
audience: player
topic: guild-stalls
summary: How guild-owned stalls and shops work — guild permissions, tariffs, and embargoes.
keywords: [guild, stall, shop, tariff, embargo, lumaguilds, policy]
related: [stalls, shop-creation, buy-sell-trade]
updated: 2026-06-25
---

# Guild stalls

Guilds can own stalls and run shops. This page explains how guild stalls differ from personal stalls.

## Buying a stall for your guild

Sneak + right-click a purchase sign on an UNOWNED stall. The guild bank is charged instead of your personal account.

You must be in a guild to do this.

## Guild permissions

Guild members need specific permissions to interact with guild stalls:

| Permission | What it allows |
|------------|---------------|
| `MANAGE_SHOPS` | Create, edit, and delete guild shops. Access shop chests. |
| `ACCESS_SHOP_CHESTS` | Open shop container inventories. |
| `EDIT_SHOP_STOCK` | Modify items in shop chests. |
| `MODIFY_SHOP_PRICES` | Change prices on shop signs. |

These are LumaGuilds guild-rank permissions — not Minecraft permission nodes. A guild leader sets them per rank.

## Guild rent

Guild stall rent can be paid from the **guild bank** instead of a personal account. The config key `lumaguilds.payFrom` controls this:

- `bank`: Rent is deducted from the guild bank.
- `leader`: Rent is deducted from the guild leader's personal account.

## Guild trade policies

Guild leaders can set economic policies against other guilds:

```text
/em guild policy
```

Requires the `MANAGE_SHOPS` guild permission.

### Tariffs

A **tariff** adds an extra fee percentage on trades between guilds.

Example: Guild A sets a 5% tariff on Guild B. When a Guild B member buys from Guild A's shop, they pay 5% extra.

### Embargoes

An **embargo** blocks all trades between guilds entirely.

If Guild A embargoes Guild B, no Guild B member can trade with Guild A's shops — and vice versa.

Both guilds must have the embargo set for a full mutual block.

## Policy announcements

When a guild sets or changes a policy, the server can broadcast it (configurable via `guildPolicy.announceEnabled`). Players entering a tariffed or embargoed stall see a warning.

## Guild dissolution

If a guild is disbanded, the plugin handles cleanup automatically — guild-owned stalls are reverted and shops are wiped.

## TRADE restriction

TRADE shops are **not available** in guild-owned stalls. Guild shops support BUY and SELL only.
