---
title: Stalls
audience: player
topic: stalls
summary: How to buy, own, and sell market stalls — lifecycle, auctions, and sellback.
keywords: [stalls, buy, sell, auction, sellback, lifecycle, purchase sign]
related: [rent, shop-creation, guild-stalls]
updated: 2026-06-25
---

# Stalls

A **stall** is a protected WorldGuard region you can own. Inside your stall, you can build, place containers, and create shops. This page covers buying, owning, and selling stalls.

## Stall lifecycle

Stalls move through these states:

| State | Meaning |
|-------|---------|
| **UNOWNED** | Available. Anyone with permission can buy it. |
| **AUCTIONING** | Up for auction. Bid with `/em bid`. |
| **OWNED** | Occupied by a player or guild. Rent applies. |
| **GRACE** | Rent not paid. Shops are frozen. Pay rent to unfreeze. After grace: emergency auction. |
| **RE_AUCTIONING** | Being re-auctioned (system recovery). |
| **EMERGENCY_AUCTIONING** | Urgent auction triggered by admin or system action. |

> **Note:** There is no separate "RENTED" state. `OWNED` covers all occupied stalls regardless of rent status.

## Buying a stall

### From a purchase sign (UNOWNED stall)

1. Find an UNOWNED stall in the market area.
2. Look for its purchase sign (first-line trigger token, stall ID on line 2, price on line 3).
3. Right-click the sign — a menu opens. Choose **Personal** to buy for yourself or **Guild** to buy for your guild.

Permission: `enthusiamarket.stall.buyout` (default: all players).

### From a sell offer

If a stall owner listed their stall for sale with `/em stall offer`, you can buy it:

```text
/em stall buy <stallId>
```

The listed price plus tax is deducted from your balance.

### Via auction

Stalls can be auctioned. Browse open auctions:

```text
/em auctions
```

Place a bid:

```text
/em bid <auctionId> <amount>
```

At auction end, the highest bidder wins. The seller receives the bid minus the auction fee (default 5%).

## Selling your stall

### Direct sale

List your OWNED stall for a fixed price:

```text
/em stall offer <stallId> <price>
```

Anyone can then buy it with `/em stall buy`. Cancel with `/em stall offer cancel <stallId>`.

### Auction

Start an auction on your stall:

```text
/em auction start <stallId> <startingPrice> [duration]
```

Duration is optional (default 24h, format: `PT24H` for 24 hours).

### Sellback (voluntary relinquish)

Give up your stall for a prorated refund:

```text
/em sellback <stallId>
```

Review the warning (refund amount, number of shops that will be wiped), then confirm within 30 seconds:

```text
/em sellback confirm <stallId>
```

## Stall members

You can add co-owners to build and manage shops inside your stall:

```text
/em stall members add <stallId> <player>
/em stall members remove <stallId> <player>
/em stall members list <stallId>
```

Permission: `enthusiamarket.stall.members`

## Stall info

See details about any stall:

```text
/em stall info <stallId>
```

Shows owner, state, rent terms, member count, and region bounds.

## View stall boundaries

Render a particle outline of a stall's region:

```text
/em stall outline <stallId> <seconds>
```

## How you lose a stall

| Way | Description |
|-----|-------------|
| **Eviction** | Rent unpaid after grace period. Shops wiped, stall back to UNOWNED. |
| **Sellback** | You chose `/em sellback`. Partial refund, shops wiped. |
| **Direct sale** | Another player bought via your sell offer. You get the price. |
| **Auction won** | Your auction resolved. Winner gets the stall, you get bid minus fee. |
| **Admin eviction** | An admin ran `/em evict`. Force-unclaimed. |
