---
title: Shop management
audience: player
topic: shop-management
summary: Manage your shops — list, edit, trust players, and delete.
keywords: [shop, management, edit, trust, delete, list, commands]
related: [shop-creation, guild-stalls]
updated: 2026-06-25
---

# Shop management

Commands for managing your existing shops.

## List your shops

```text
/shop list
```

Shows every shop you own with its location, item, direction, and price.

## Edit a shop

```text
/shop edit
```

Opens a GUI listing all your shops. Click one to edit:

- Change the per-trade amount.
- Change the price.
- Toggle search visibility.
- Toggle frozen state (pause trading).

## Trust a player

Let another player manage your shops:

```text
/shop trust <player>
```

Opens a bulk-trust GUI where you pick which shops to share.

```text
/shop trust <player> all
```

Trusts that player on **all** your shops.

Trusted players can edit shop settings but cannot delete shops.

## Untrust a player

```text
/shop untrust <player> [mode]
```

Removes a player's access. Mode `all` removes from every shop.

## Delete shops

```text
/shop delete
```

Opens a GUI where you pick which shop to delete.

```text
/shop delete all
```

Deletes **all** your shops. Requires `enthusiamarket.shop.delete.all`.

## Break-delete mode

```text
/shop breakdelete
```

Toggles break-delete: while active, breaking a shop's sign also deletes the shop record.

```text
/shop breakdelete 5m
/shop breakdelete off
```

Set a duration or turn it off.

## Transaction history

```text
/shop history [page]
```

View your shop transaction history (paginated, 10 per page).

## Tutorial

```text
/shophelp show
```

Displays a multi-line tutorial from the language files. Good for new players.

## Admin commands

These require `enthusiamarket.admin.shop`:

| Command | Description |
|---------|-------------|
| `/shop admin view` | Open shop edit GUI for the shop you're looking at |
| `/shop admin info` | Show detailed info for the shop you're looking at |
| `/shop admin remove` | Admin-delete the shop you're looking at |
| `/shop admin fix` | Re-render sign and check container for the shop you're looking at |
| `/shop admin breakothers [mode]` | Toggle bypass for sign break protection |
| `/shop admin vault <player>` | Open another player's shop vault |
| `/shop admin contents` | View container contents of the shop you're looking at |
