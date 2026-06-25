---
title: Creating shops
audience: player
topic: shop-creation
summary: How to create BUY, SELL, and TRADE shops — sign placement, GUI choices, and per-trade settings.
keywords: [shop, create, sign, buy, sell, trade, barter, gui]
related: [buy-sell-trade, barter-vault, shop-management, guild-stalls]
updated: 2026-06-25
---

# Creating shops

Shops are the core of EnthusiaMarket. You place a sign on a container inside your stall, pick what you want to trade, set a price, and you're in business.

## Requirements

- You must **own the stall** (or have member access).
- For guild stalls, you need the `MANAGE_SHOPS` guild permission.
- You need the `enthusiamarket.shop.create` permission (granted by default).

## Step-by-step

### 1. Place a wall sign on a container

Attach a wall sign to a **chest, barrel, shulker box, or other container** inside your stall. The shop creation GUI opens automatically.

> **Empty container?** If the container is empty, you'll see an error and the sign is cancelled. Put items in the container first.

### 2. Choose trade direction

The GUI shows three buttons:

- **SELL** — You sell items from the container to other players. They pay you.
- **BUY** — You buy items from other players. You pay them. Your economy balance is checked to ensure you can afford the total stock.
- **TRADE** — Item-for-item exchange. Players give you a specific item to receive the container's item.

### 3. Set per-trade amount

How many items change hands per transaction. Example: set 16 to sell/buy 16 items per click.

### 4. Set your price

For **BUY** and **SELL** shops: a currency amount in the server's economy unit.

For **TRADE** shops: hold the payment item in your hand, then click the barter cost button in the GUI. The plugin captures the item type and amount.

### 5. Confirm

Click the confirm button. The plugin:

1. Creates the shop record.
2. Writes the sign text (direction, item, amount, price).
3. Links the sign to your container.

Your shop is now live.

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
| "Container is empty" | Put items in the container, then place the sign. |
| No GUI opens | You're on Bedrock — the form opens instead. If neither appears, check your permissions. |
| Guild shops require permission | Ask a guild leader to grant you `MANAGE_SHOPS`. |
