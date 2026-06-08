---
title: Shops
audience: player
topic: shops
summary: How to create and manage sign shops — sell, buy, barter, trust, vault, and search.
keywords: [shops, sign shop, sell, buy, barter, vault, trust, search]
related: [stalls, rent]
updated: 2026-06-06
---

# Shops

Shops let you trade items with other players from inside your stall. You create them by placing signs on containers.

## Creating a shop

You need the `enthusiamarket.shop.create` permission (granted to players by default).

1. **Hold the item** the shop deals in — the item in your hand defines what's bought/sold/traded.
2. Place a **wall sign** on a container (chest, barrel, etc.) inside your stall.
3. Line 1: the **direction token** — `[SELL]`, `[BUY]`, or `[TRADE]`. This is **required** (there is no default); brackets are optional and case doesn't matter, so `SELL` works too.
4. Line 2: **quantity** per trade (e.g., `64`).
5. Line 3: **price** in server currency (e.g., `1000`). For `[TRADE]` shops this is instead the item + amount you charge, e.g. `10xdiamond`.
6. The sign auto-formats into a working shop (it's rejected, with a message, if it isn't a wall sign on a container inside a stall you manage).

## Trade directions

| Direction | Line 1 token | What happens | Line 3 |
|-----------|--------------|-------------|--------|
| **SELL** | `[SELL]` | You sell items to players. They pay you, receive items from your container. | Price (e.g., `1000`) |
| **BUY** | `[BUY]` | You buy items from players. They give you items, receive payment. | Price (e.g., `1000`) |
| **TRADE** | `[TRADE]` | Barter: players give you one item, receive another. | `10xdiamond` (what you charge) |

### Sell shops

The most common type. You stock the container with items. Players click the sign, pay the listed price, and receive the listed quantity.

- Payment goes to your [shop vault](#shop-vault).
- Container must have enough stock.
- Default tax: 2% (configurable).

### Buy shops

You set a price you're willing to pay. Players click the sign to sell items to you.

- Items go into your container.
- Payment comes from your wallet.
- Useful for automating resource collection.

### Trade (barter) shops

Players trade one item for another. No currency changes hands.

- Line 2: quantity of items you want to receive.
- Line 3: quantity and item you give in return (e.g., `10xdiamond`).

## Trusting players

Let other players manage your shop without giving them stall ownership:

```
/shop trust <player>
```

This opens a menu where you can select which shops to share. To trust on all shops at once:

```
/shop trust <player> all
```

## Untrusting players

```
/shop untrust <player>
```

Removes the player from all your shops.

## Editing shops

```
/shop edit
```

Opens a menu showing all your shops. Click one to change price, quantity, direction, or other settings.

## Deleting shops

```
/shop delete
```

Opens a menu to select shops for deletion. To delete all shops at once:

```
/shop delete all
```

Requires the `enthusiamarket.shop.delete.all` permission.

## Break-delete mode

Temporarily break a shop sign to delete it:

```
/shop breakdelete <duration>
```

Then punch the shop sign to remove it. Mode turns off automatically after the duration, or manually:

```
/shop breakdelete off
```

## Shop vault

Money from sell shops goes to your shop vault. Open it with:

```
/shopvault open
```

or the alias:

```
/svault open
```

The vault is a menu showing your earnings. Click to withdraw.

## Searching for shops

Find shops selling a specific item:

```
/shop search <item>
```

For example, `/shop search diamond` shows all shops selling diamonds. Results are paginated.

## Transaction history

View your shop's sales history:

```
/shop history [page]
```

Shows date, item, quantity, price, and buyer name.
