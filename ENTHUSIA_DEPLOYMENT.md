# Enthusia deployment note for this fork

This repository is a **noncanonical fork** of `BadgersMC/EnthusiaMarket`.

Do not treat this fork's `main` branch as the primary Market implementation source for Enthusia wiki generation.

Canonical/current routing is maintained in:

- `BadgersMC/EnthusiaMarket` — implementation and detailed `wiki/docs/players/` documentation.
- `wsg138/enthusia-server-state/repo-overlays/BadgersMC-EnthusiaMarket.md` — current Enthusia deployment values/toggles.
- `wsg138/enthusia-site` — public website presentation.

A current-upstream documentation candidate exists on this fork at branch:

`docs/current-enthusia-deployment`

The current production backend contains `EnthusiaMarket-1.0.47.jar`. Current live configuration includes one default stall, flat 100/day rent with a 3-day grace period, current shop/auction settings, LumaGuilds integration, Bedrock editing, schematic reset, and enabled website synchronization. Exact values should come from the central deployment overlay rather than this stale fork branch.

`ItemShops` is superseded and `EnthusiaMarketMapper` is a one-time/offline mapping tool; neither is the live Market authority.