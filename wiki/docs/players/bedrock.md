---
title: Bedrock Edition
audience: player
topic: bedrock
summary: What's different for Bedrock players — forms, shops, and known limitations.
keywords: [bedrock, floodgate, forms, cumulus]
related: [shops, stalls]
updated: 2026-06-06
---

# Bedrock Edition

EnthusiaMarket supports Bedrock players through Floodgate. Most features work the same, but there are some differences.

## What works the same

- Bidding on auctions (`/em bid`).
- Paying rent (click the stall sign).
- Trading at shops (click shop signs).
- Viewing auction browser (`/em auctions`).

## What's different

### Forms instead of signs

Bedrock players can't edit sign text the same way Java players do. EnthusiaMarket provides **Cumulus forms** (Bedrock-native UI) for:

- Creating and editing shops.
- Managing trust.
- Browsing auctions.

When you place a shop sign, a form opens automatically to configure it.

### Shop creation

Instead of typing on the sign:

1. Place a sign on a container.
2. A form opens asking for quantity, price, and direction.
3. Submit the form — the shop is created.

### Menu interactions

Right-click menus (like `/shop edit`) open as Bedrock forms instead of chat menus.

## Known limitations

- Some advanced sign formatting may not render identically on Bedrock.
- Form timeout: 60 seconds (configurable). If you don't complete the form in time, you'll need to start over.

## Troubleshooting

**Q: The form isn't opening when I place a sign.**
A: Make sure Floodgate is installed and you're connected as a Bedrock player. Ask staff to check the `forceForms` config option.

**Q: I can't edit a shop sign.**
A: Bedrock players use forms, not sign editing. Use `/shop edit` to open the edit form.
