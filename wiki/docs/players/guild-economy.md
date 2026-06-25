---
title: Guild Economy
audience: player
topic: guild-economy
summary: How guilds tariff or embargo other guilds at their shops, and how to manage policy with /em guild policy.
keywords: [guild, tariff, embargo, trade policy, economy, sanctions]
related: [shops, stalls]
updated: 2026-06-08
---

# Guild Economy — Tariffs & Embargoes

If your guild owns stalls and shops, it can wage **economic warfare** on other guilds: make their members pay more (a **tariff**) or block them entirely (an **embargo**) at your shops.

Policies are set **per other guild** — you decide, for each rival guild, whether they trade normally, at a worse rate, or not at all.

## Who a policy affects

A policy only applies to members of the **targeted guild**. Everyone else trades at the normal price:

- **Solo players** (no guild) — always pay the base price, never embargoed.
- **Your own guild's members** — always pay the base price.
- **Members of a guild you have no policy on** — base price.

## Tariffs

A tariff is a percentage that makes the targeted guild trade at a **worse rate**, both directions — and your guild bank keeps the difference:

| Shop type | What the outsider does | A 20% tariff means… |
|-----------|------------------------|---------------------|
| **SELL** shop (you sell items) | pays you | they pay **120%** — the extra 20% goes to your guild bank |
| **BUY** shop (you buy items) | gets paid by you | they receive **80%** — your guild keeps the other 20% |

Tariffs range from **0% to 99%**. To shut a guild out completely, use an **embargo** — a 100% tariff isn't allowed, because on a BUY shop it would mean taking a player's items for nothing.

## Embargoes

An embargo **blocks the targeted guild's members from trading at your shops at all**. When one tries, the trade is cleanly rejected — no items or money move. It's the tool for a total trade ban.

## Managing policy — `/em guild policy`

Run **`/em guild policy`** to open the management menu. You need the **MANAGE_SHOPS** permission in your guild (the same one used to register guild shops).

The menu lists your guild's current policies. Each entry shows the target guild and its current tariff/embargo. Click an entry to change it:

| Click | Effect |
|-------|--------|
| **Left-click** | tariff **+5%** (max 99%) |
| **Right-click** | tariff **−5%** (min 5%) |
| **Shift-left-click** | switch to **embargo** |
| **Shift-right-click** | **remove** the policy |

The **➕ Add a guild** button opens a picker listing every other guild you don't yet have a policy on. Selecting one starts it at a default **10% tariff**, which you can then adjust.

## Notifications

Tariffs and embargoes are public — you'll always know where you stand:

- **Server broadcast** — whenever any guild sets, changes, or lifts a tariff/embargo, the whole server is told (e.g. *"Alpha has imposed a 20% tariff on Beta"*).
- **Entry warning** — when you walk into a guild's stall where **your** guild is tariffed or embargoed, you get a personal message with the exact rate, so you know before you trade.

> **Note:** Server admins can toggle either notification off in the config.

## Quick reference

- **See/set policy:** `/em guild policy` (needs MANAGE_SHOPS).
- **Tariff range:** 0–99%. Hits outsiders worse both buying and selling; the surcharge funds your guild bank.
- **Embargo:** total block; the trade is refused, nothing moves.
- **Never affected:** solo players and your own guild.
