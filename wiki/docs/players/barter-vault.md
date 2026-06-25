---
title: Barter vault
audience: player
topic: barter-vault
summary: How TRADE shop payments work — collecting items from your shop vault.
keywords: [barter, trade, vault, shopvault, svault, payment]
related: [shop-creation, buy-sell-trade]
updated: 2026-06-25
---

# Barter vault

When you run a **TRADE** shop, players pay you in items — not money. Those payment items go to your **shop vault**.

## Opening your vault

```text
/shopvault open
```

Alias: `svault`

A GUI opens showing all the items players have paid you. Click to withdraw.

## What goes into the vault

Every TRADE transaction deposits the payment items into your vault. The vault is per-player — all your TRADE shops feed into the same pool.

## Admin access

Admins can view another player's vault:

```text
/shop admin vault <player>
```

Requires `enthusiamarket.admin.shop` permission.

## Notes

- The vault is **not** a physical chest. It's a virtual inventory the plugin manages.
- Only the shop owner can open their vault (unless an admin overrides).
- Items are stored indefinitely until withdrawn.
