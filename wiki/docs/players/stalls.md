---
title: Stalls
audience: player
topic: stalls
summary: How stalls work — ownership, members, sellback, and sell offers.
keywords: [stalls, ownership, members, sellback, sell offer]
related: [auctions, rent, shops]
updated: 2026-06-06
---

# Stalls

Stalls are the foundation of EnthusiaMarket. Every shop, trade, and rental flows through stall ownership.

## What is a stall?

A stall is a WorldGuard-protected region in the market world. It gives you:

- A protected build area where only you (and your members) can build.
- The ability to create sign shops inside the region.
- Responsibility to pay rent to keep the stall.

## Stall states

| State | Meaning |
|-------|---------|
| **UNOWNED** | No one owns it. Available for auction. |
| **AUCTIONING** | An auction is active. Place bids with `/em bid`. |
| **OWNED** | A player or guild owns it. Rent is being paid. |
| **GRACE** | Rent is overdue. A 3-day countdown before re-auction. |
| **RE_AUCTIONING** | Grace expired. Being re-auctioned to the public. |
| **EMERGENCY_AUCTIONING** | Admin-triggered re-auction. |

## Ownership types

A stall can be owned by:

- **Solo** — one player owns it.
- **Guild** — a LumaGuilds guild owns it. Guild members with `MANAGE_SHOPS` permission can manage it.
- **None** — unowned, available for auction.

## Adding members

Members can build and interact inside your stall but don't own it. To add a member, the stall owner (or authorized guild member) uses:

```
/em stall members add <stall-id> <player>
```

Members can be listed with:

```
/em stall members list <stall-id>
```

Each stall has a configurable member cap (default: unlimited).

## Removing members

```
/em stall members remove <stall-id> <player>
```

## Sellback

Sell your stall back to the system for a refund. This is a two-step confirmation:

1. `/em sellback <stall-id>` — see the refund quote and warnings.
2. `/em sellback confirm <stall-id>` — confirm within 30 seconds.

The refund is based on your winning bid and remaining rent periods. All shops inside the stall are **wiped** — withdraw items first.

## Sell offers

Offer your stall for sale to another player:

- `/em stall offer <stall-id> <price>` — create a sell offer.
- `/em stall offer cancel <stall-id>` — cancel your offer.
- `/em stall buy <stall-id>` — buy a stall someone else offered.

The buyer pays the listed price plus tax. The seller receives the price minus the auction fee (default: 5%).
