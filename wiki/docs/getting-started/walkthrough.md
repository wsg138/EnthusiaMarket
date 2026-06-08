---
title: Your First 30 Minutes
audience: player
topic: walkthrough
summary: Step-by-step guided tour for new players — from finding the market to making your first sale.
keywords: [walkthrough, tutorial, first steps, beginner]
related: [welcome, faq]
updated: 2026-06-06
---

# Your First 30 Minutes

This walkthrough takes you from arriving at the market to running your first shop. Set aside about 30 minutes.

## Step 1: Find the market

The market is a dedicated area on the server. Ask staff or other players for the current market location. You'll see rows of stall plots with sign posts.

## Step 2: Find an available stall

Look for stalls with signs showing **"Available"** or similar. These are either unowned or currently at auction. You can also use `/em auctions` to browse active auctions via the menu.

## Step 3: Bid on a stall

When an auction is running, you can bid:

```
/em bid <auction-id> <amount>
```

Type `/em auctions` to see active auctions and their IDs. Enter your bid amount — it must exceed the current high bid.

> **Tip:** If a bid is placed near the end of an auction, the timer extends by 30 seconds (anti-snipe). Don't wait until the last second!

## Step 4: Win and own

If you have the highest bid when the auction ends, you own the stall. You'll get a confirmation message. The stall sign updates to show your name.

## Step 5: Pay rent

Rent is charged daily. The amount depends on your winning bid (default: 1% of winning bid per day). When you click your stall's sign, you'll see the rent due and time remaining.

To pay rent, simply **click the stall sign** and confirm. The payment comes from your wallet.

> **Warning:** If you miss rent, your stall enters a 3-day grace period. After grace expires, the stall is re-auctioned and you lose it.

## Step 6: Set up a shop

Inside your stall, place a **sign on a container** (chest, barrel, etc.) to create a shop:

1. Place a sign on the side of a container.
2. On the first line, type `[em]` (the default trigger token).
3. On the second line, type the **quantity** you want to sell.
4. On the third line, type the **price**.
5. The sign auto-formats into a working shop.

Now other players can click your sign to buy from your container.

## Step 7: Make your first sale

Stock your container with items. When another player clicks your sign and the trade succeeds, the payment goes to your **shop vault**. Open it with:

```
/shopvault open
```

or the alias `/svault open`.

## Next steps

- Learn about [trade directions](../players/shops.md#trade-directions) — sell to players, buy from players, or barter.
- Understand [rent mechanics](../players/rent.md) in detail.
- Check [ownership limits](../players/limits.md) if you want multiple stalls.
- Browse the full [How do I…?](../players/how-do-i.md) index.
