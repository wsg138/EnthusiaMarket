---
title: Buying, selling, and trading
audience: player
topic: buy-sell-trade
summary: How to use other players' shops — BUY, SELL, and TRADE transactions explained.
keywords: [buy, sell, trade, transaction, customer, shop]
related: [shop-creation, barter-vault, guild-stalls]
updated: 2026-06-25
---

# Buying, selling, and trading

You don't need a stall to use the market. Right-click any player shop sign to trade.

## SELL shops (buy from a shop)

The shop owner is selling items. You're the buyer.

1. Right-click the sign.
2. The purchase GUI shows:
   - The item, per-trade amount, and price.
   - How many trades are available in the container.
   - The shop owner's name.
3. Click to buy.
4. Money goes from you → the shop owner. Items go from the container → your inventory.

> **Out of stock?** The stock count on the sign drops to zero. The owner needs to restock the container.

## BUY shops (sell to a shop)

The shop owner is buying items. You're the seller.

1. Right-click the sign.
2. The purchase GUI shows what the shop is buying and the price.
3. Have the requested items in your inventory.
4. Click to sell.
5. Items go from your inventory → the container. Money goes from the shop owner → you.

> **Shop owner out of money?** BUY shops check the owner's economy balance. If they can't afford the trade, you'll see a notification.

## TRADE shops (barter)

Item-for-item exchange. No money involved.

1. Right-click the sign.
2. The GUI shows the item you'll receive and the payment item you need.
3. Have the payment items in your inventory.
4. Click to trade.
5. Your payment items go to the **shop owner's vault**. The container items go to your inventory.

> **To collect barter payments,** the shop owner uses `/shopvault open`.

## Understanding the purchase GUI

When you right-click a shop sign, the GUI shows:

| Element | Meaning |
|---------|---------|
| **Item icon** | What you're buying or selling. |
| **Per-trade amount** | How many items per click. |
| **Cost** | Currency amount (BUY/SELL) or payment item (TRADE). |
| **Direction label** | "Buying from shop" / "Selling to shop" / "Trading". |
| **Trades available** | How many more transactions the shop can handle. |
| **Owner** | Who runs this shop. |

## Tax

Every trade has a small tax (default 2%). The tax is deducted from the transaction and routed to the server's tax destination.

Example: You buy an item for $100. The seller receives $98. $2 goes to tax.

Guild tariffs can add additional fees (see **[Guild stalls](guild-stalls.md)**).
