---
title: Bedrock differences
audience: player
topic: bedrock
summary: What's different when playing EnthusiaMarket on Bedrock Edition.
keywords: [bedrock, geyser, floodgate, cumulus, forms, crossplay]
related: [shop-creation, buy-sell-trade, shop-management]
updated: 2026-06-25
---

# Bedrock differences

EnthusiaMarket supports Bedrock players through Geyser and Floodgate. Most features work the same, with a few differences.

## Menus

Java players see **inventory GUIs** (chest menus). Bedrock players see **Cumulus forms** — chat-based windows with buttons.

The same actions are available; the presentation differs:

- **Shop creation**: A form with dropdowns for direction, amount, and price.
- **Shop purchase**: A form showing the item, cost, and trade count.
- **Shop edit**: A form version of the Java edit GUI.

## Sign interactions

Right-clicking and sneak+click work the same on Bedrock. The shop signs update identically.

## Config

Admins can control Bedrock behaviour:

| Key | Default | Meaning |
|-----|---------|---------|
| `bedrock.forceForms` | `false` | Use Cumulus forms even without Floodgate (for testing). |
| `bedrock.formTimeoutSec` | `60` | Seconds before a form auto-closes. |
| `shop.allowBedrockEdit` | `true` | Allow Bedrock players to edit sign content via form. |

## Placeholders

PlaceholderAPI expansions (`%enthusiamarket_*%`) work the same across both editions.

## Known limitations

- Some GUIs with complex layouts may render differently as forms.
- If a form times out, reopen it by repeating the command or clicking the sign again.
