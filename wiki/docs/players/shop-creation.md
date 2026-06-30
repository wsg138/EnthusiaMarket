---
title: Creating shops
audience: player
topic: shop-creation
summary: "How to create BUY, SELL, and TRADE shops — shift-click for the GUI or place a sign with text."
keywords: [shop, create, sign, buy, sell, trade, barter, gui, shift-click]
related: [buy-sell-trade, barter-vault, shop-management, guild-stalls]
updated: 2026-06-29
---

# Creating shops

Shops are the core of EnthusiaMarket. You link a container to a wall sign inside your stall, configure what you trade and at what price, and you're in business.

There are two ways to create a shop — the GUI method (recommended) and the text-sign method (fallback).

## Method 1: GUI (recommended)

This method opens a menu where you pick the trade direction, amounts, and payment item without typing anything on the sign.

### Step-by-step

1. **Place a blank wall sign** on a chest, barrel, shulker box, or other container **inside your stall**.
2. **Hold the item you want to trade** in your main hand.
3. **Shift + left-click the sign.** The shop creation GUI opens.
4. **Choose trade direction** — SELL, BUY, or TRADE (item-for-item).
5. **Set per-trade amount** — how many items change hands per click.
6. **Set your price** — currency amount for BUY/SELL, or hold the payment item for TRADE.
7. **Confirm** — the plugin creates the shop, writes the sign text, and links everything.

> **Bedrock players:** A form opens instead of the Java GUI. The process is the same — pick trade direction, amounts, and confirm.

## Method 2: Text sign (fallback)

The classic sign-based method from earlier versions. Write the trade details directly on the sign.

### Step-by-step

1. **Place a wall sign** on a container inside your stall.
2. **Hold the item you want to trade** in your main hand.
3. **Write on the sign** (before confirming):
   - **Line 1:** `[BUY]`, `[SELL]`, or `[TRADE]`
   - **Line 2:** Amount per trade (e.g. `64`)
   - **Line 3:** Price (e.g. `1000` for currency, or `16 DIAMOND` for TRADE)
4. **Confirm the sign.** The plugin validates your input, creates the shop, and reformats the sign text.

> **Tip:** Both methods produce the same result. Use whichever you find faster.

## Sign text

After creation, your sign reads:

- **Line 1**: `[SELL]`, `[BUY]`, or `[TRADE]`
- **Line 2**: The item being traded
- **Line 3**: Amount × Price
- **Line 4**: `[Shop]`

## What can go wrong

| Problem | Fix |
|---------|-----|
| "You don't own a stall in this region" | Place the sign inside a stall you own. |
| "Container is empty" | Put items in the container first. |
| No GUI opens on shift+click | You might be on Bedrock — the form opens instead. If neither appears, check your permissions. |
| Guild shops require permission | Ask a guild leader to grant you `MANAGE_SHOPS`. |
| Old text-sign method doesn't work | Make sure line 1 is exactly `[BUY]`, `[SELL]`, or `[TRADE]` — uppercase, with brackets. |
