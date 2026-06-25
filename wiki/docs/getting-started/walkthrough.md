---
title: Your first stall
audience: player
topic: walkthrough
summary: Step-by-step guide to buying a stall, understanding rent, and setting up your first shop.
keywords: [walkthrough, first stall, buying, rent, shop setup]
related: [welcome, faq, stalls, rent, shop-creation]
updated: 2026-06-25
---

# Your first stall

This walkthrough takes you from zero to running your own shop in about 10 minutes.

## Step 1: Find an available stall

Walk around the market area and look for a stall with a purchase sign. The sign shows:

- **Line 1**: `[em]` — the purchase sign token.
- **Line 2**: The stall ID (e.g. `stall42`).
- **Line 3**: The buyout price.
- **Line 4**: Who owns it — or `Buy now!` if the stall is available.

Stalls in the `UNOWNED` state are available for purchase. The purchase sign's third line shows the price.

## Step 2: Buy the stall

1. **Right-click** the purchase sign to buy it for yourself.
2. Confirm the purchase when prompted.
3. The price is withdrawn from your economy balance and the stall is now yours.

> **For guilds:** Sneak + right-click the purchase sign to buy the stall for your guild instead of yourself. The guild bank is charged.

## Step 3: Understand rent

Every stall you own has rent. By default:

- **Rent mode**: Formula-based (1% of purchase price per day).
- **Collection**: Rent is collected once per day (configurable).
- **Grace period**: If you miss a payment, you have 3 days to catch up before eviction.

You can **pre-pay rent** by right-clicking the purchase sign twice within 10 seconds. Each double-click extends rent by one period.

Check `/em limit` to see how many stalls you're allowed to own.

## Step 4: Set up a shop

1. Place a **wall sign** on a chest, barrel, or shulker box inside your stall region.
2. The shop creation GUI opens automatically — the container's contents are scanned.
3. Pick your trade direction:
   - **SELL** — sell items from the container to players (you receive money).
   - **BUY** — buy items from players (you pay them).
   - **TRADE** — exchange items for other items (barter).
4. Set the per-trade amount (how many items per transaction).
5. Set your price (currency amount for BUY/SELL, or barter item for TRADE).
6. Confirm — the sign text is written for you automatically.

> **Important:** The shop sign and the container must be inside a stall you own (or have member access to). Guild stalls require the `MANAGE_SHOPS` guild permission.

## Step 5: Test your shop

1. Look at your shop sign — it should show the direction, item, amount, and price.
2. Have a friend right-click the sign to test a transaction.
3. Money goes to/from your economy account; items transfer between your container and the customer.

## Next steps

- **[Manage your shops](../players/shop-management.md)** — trust friends, edit prices, delete shops.
- **[Search for goods](../players/shop-search.md)** — find what other players are selling.
- **[Understand rent](../players/rent.md)** — avoid eviction.
- **[Sell your stall](../players/stalls.md)** — list it for sale.
