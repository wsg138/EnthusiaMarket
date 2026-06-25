---
title: FAQ
audience: player
topic: faq
summary: Common questions from new players about EnthusiaMarket.
keywords: [faq, common questions, help, troubleshooting]
related: [walkthrough, stalls, rent, shop-creation]
updated: 2026-06-25
---

# FAQ

## How do I get a stall?

Find an `UNOWNED` stall with a purchase sign. Right-click the sign to buy it. You need the `enthusiamarket.stall.buyout` permission (granted by default).

## Why can't I place a shop sign?

The sign must be on a **container** (chest, barrel, shulker box) inside a stall you **own** (or have member access to). For guild stalls, you need the `MANAGE_SHOPS` guild permission.

## What's the difference between BUY, SELL, and TRADE?

- **SELL**: You sell items from your container. Players pay you. Container must have stock.
- **BUY**: You buy items from players. You pay them. Stock (currency available) is checked against your economy balance.
- **TRADE**: Item-for-item exchange. Players give you one item to receive another.

## How do I get paid from TRADE shops?

Items from TRADE transactions go to your **shop vault**. Open it with `/shopvault open`.

## How is rent calculated?

Rent depends on your server's config:

- **Formula mode**: `purchasePrice × rentPct%` per day (e.g. 1% of purchase price).
- **Flat mode**: A fixed amount per period regardless of stall price.

Check the current rent on your stall by double-clicking the purchase sign.

## What happens if I can't pay rent?

Your stall enters a **grace period** (3 days by default). During grace, the stall is still yours and shops still work. If you pay rent (double-click the purchase sign twice), you're back in good standing. If the grace period expires, you're **evicted** — all shops are wiped and the stall returns to UNOWNED.

## Can I sell my stall?

Yes. Use `/em stall offer <stallId> <price>` to create a direct-sale listing. Another player buys it with `/em stall buy <stallId>`. You receive the listed price minus tax.

Alternatively, use `/em sellback` for a prorated refund (partial, not the full purchase price).

## How do I search for items to buy?

```text
/shop search <item>
```

Tab-complete works for item names. Add `buy` or `sell` to filter by direction. Results show in a GUI with live stock counts.

## I'm on Bedrock — does everything work?

Mostly. Creation and purchase menus open as Cumulus forms instead of inventory GUIs. Sign interactions work the same way. See **[Bedrock differences](../players/bedrock.md)** for details.

## How do I list my shop for others to find?

New shops are searchable by default (`/shop search`). You can toggle search visibility in the shop edit GUI (`/shop edit`).

## What are guild tariffs and embargoes?

Guild leaders can set trade policies against other guilds:

- **Tariff**: Extra fee percentage on trades between guilds.
- **Embargo**: Complete block on all trades between guilds.

Manage these with `/em guild policy` (requires `MANAGE_SHOPS` guild permission).

## Where do I report a bug?

Report it in our Discord, or open an issue at <https://github.com/BadgersMC/EnthusiaMarket/issues>.
