---
title: Shop search
audience: player
topic: shop-search
summary: How to find shops selling what you need with /shop search.
keywords: [shop, search, find, lookup, query]
related: [buy-sell-trade, shop-creation]
updated: 2026-06-25
---

# Shop search

Find shops selling what you need — or buying what you have.

```text
/shop search <item>
```

## Basic search

Type any Minecraft item name. Tab-complete is supported — press Tab to see material names matching your typed prefix (case-insensitive).

```text
/shop search diamond
/shop search ender_pearl
/shop search oak_log
```

Results open in a GUI showing:

- The item and shop direction (BUY/SELL/TRADE).
- Per-trade amount, price, and remaining stock.
- Shop owner and location.

## Filter by direction

Add a mode after your query:

```text
/shop search diamond sell    — only shops selling diamonds
/shop search dirt buy        — only shops buying dirt
/shop search ender_pearl any — all directions (default)
```

## Pagination

If there are many results, specify a page number:

```text
/shop search diamond sell 2
```

## What shops are searchable

New shops are **searchable by default**. Shop owners can toggle this in the edit GUI (`/shop edit`). The server can change the default via the `shop.search.default` config key.

## Tips

- **Sell search** means you're looking to BUY from shops that SELL. You're the customer.
- **Buy search** means you're looking to SELL to shops that BUY. You're the supplier.
- Results show live stock counts — if it says 0, the container is empty.
