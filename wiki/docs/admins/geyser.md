---
title: Geyser & Bedrock setup
audience: admin
topic: geyser
summary: How to configure Geyser and Floodgate for Bedrock cross-play support.
keywords: [geyser, floodgate, bedrock, cumulus, forms]
related: [installation, integration, bedrock]
updated: 2026-06-25
---

# Geyser & Bedrock setup

EnthusiaMarket supports Bedrock players through Geyser and Floodgate. No special config is needed beyond installing the plugins.

## Requirements

- Geyser (latest)
- Floodgate (latest)

Both are soft dependencies — the plugin works without them.

## How it works

Floodgate provides an API to detect Bedrock players. When EnthusiaMarket detects a Bedrock player, it opens **Cumulus forms** instead of Java inventory GUIs.

The forms have the same functionality but use chat-based menus instead of chest GUIs.

## Config

| Key | Default | Purpose |
|-----|---------|---------|
| `bedrock.forceForms` | `false` | Use forms even without Floodgate (for testing). |
| `bedrock.formTimeoutSec` | `60` | Seconds before a form auto-closes. |
| `shop.allowBedrockEdit` | `true` | Allow Bedrock players to edit sign content. |

## Forms affected

- **Shop creation** — direction, amount, price selection.
- **Shop purchase** — item display, cost, trade count.
- **Shop edit** — edit price, amount, search toggle.
- **Shop management** — bulk trust, delete picker.

## Troubleshooting

**Forms don't open for Bedrock players:**

- Check Floodgate is installed and the player joined through Geyser.
- Verify the player has `enthusiamarket.shop.use` permission.
- Toggle `bedrock.forceForms: true` and test.

**Forms time out:**

- Increase `bedrock.formTimeoutSec`.
- Default is 60 seconds — long enough for most interactions.

**Bedrock players can't edit signs:**

- Set `shop.allowBedrockEdit: true`.
- The player must own or be trusted on the shop.
