---
title: Integration
audience: admin
topic: integration
summary: How EnthusiaMarket integrates with WorldGuard, Vault, LumaGuilds, Geyser, PlaceholderAPI, WorldEdit, and EnthusiaCurrency.
keywords: [integration, worldguard, vault, lumaguilds, geyser, floodgate, placeholderapi, worldedit, fawe]
related: [installation, config]
updated: 2026-06-25
---

# Integration

EnthusiaMarket depends on and integrates with several plugins.

## Hard dependencies

These plugins **must** be installed. EnthusiaMarket refuses to enable without them.

### WorldGuard

Stall regions are WorldGuard regions. EnthusiaMarket manages region ownership, ACL synchronization, and flag provisioning.

- Region prefix: configured via `market.regionPrefix` (default: `stall`).
- Priority: `market.stallPriority` must exceed surrounding safezone regions.
- Flags: BUILD, CHEST_ACCESS, BLOCK_PLACE, BLOCK_BREAK, USE, PISTONS, and more.

Commands:

- `/em import` — register matching WG regions as stalls.
- `/em rg resync` — rebuild region ACLs from stall data.

### Vault

Economy abstraction. All money operations go through Vault.

### EnthusiaCurrency

The actual Vault economy provider. Fallback if missing: rent, auctions, and shops are disabled.

### LumaGuilds

Guild integration. Handles guild-owned stalls, guild bank rent payments, guild permissions (MANAGE_SHOPS, etc.), and trade policies (tariffs/embargoes).

Configurable via `lumaguilds.enabled` and `lumaguilds.payFrom`.

## Soft dependencies

These are optional. The plugin works without them but gains features when present.

### PlaceholderAPI

Provides `%enthusiamarket_*%` placeholders for scoreboards, chat, and other PAPI consumers. Also used for purchase sign owner name templates.

### WorldEdit / FastAsyncWorldEdit

Stall schematic snapshots and restores. When a stall is claimed, the plugin saves a schematic. On eviction or sellback, the schematic is restored.

- FAWE is preferred when present (async operations).
- Disable entirely with `schematics.enabled: false`.

### Geyser / Floodgate

Bedrock cross-play support. Floodgate detects Bedrock players and routes them to Cumulus forms instead of Java inventory GUIs.

- Toggle with `bedrock.forceForms`.
- Bedrock sign editing: `shop.allowBedrockEdit`.

## Not referenced

| Plugin | Status |
|--------|--------|
| RoseChat | Not integrated — no references in codebase |
| Lunar Client | Not integrated — no references in codebase |
