# Current Enthusia SMP deployment

The canonical repository is `BadgersMC/EnthusiaMarket`. Its repository wiki under `wiki/docs/players/` describes the generic player-facing feature set. This file records the important current Enthusia SMP production settings visible in the latest sanitized `wsg138/enthusia-server-state` snapshot.

This branch is based directly on current canonical upstream `BadgersMC/EnthusiaMarket` and exists as a documentation candidate because the connected GitHub integration cannot write the BadgersMC repository directly.

## Status and authority

The current production JAR manifest contains `EnthusiaMarket-1.0.47.jar`.

Use:

- `BadgersMC/EnthusiaMarket` code for implementation semantics,
- its `wiki/docs/players/` pages for generic player instructions,
- the latest `enthusia-server-state` snapshot for current Enthusia-specific numbers and toggles,
- `wsg138/enthusia-site` for public website presentation.

Older `ItemShops` and `EnthusiaMarketMapper` repositories are not the live market authority.

## Current player feature set

The live implementation supports WorldGuard-region market stalls, player/guild stalls, container-backed shops, search, auctions, stall management, rent/eviction, stall schematic reset, Bedrock forms, LumaGuilds integration and public website synchronization.

For detailed player procedures, prefer the existing canonical pages in `wiki/docs/players/` instead of duplicating every interaction here.

## Stall limits and fairness controls

Current live configuration:

- default stall limit with no special limit-group permission: **1**
- IP fairness limiter: **one owned stall per IP**
- IP fairness limiter: **one active auction bid per IP**

Permission-based limit groups can raise/change effective stall limits. IP limits are anti-abuse controls, not proof that different players are the same person; shared households can legitimately share an IP.

## Rent

Current live rent settings:

- collection interval: **1 day**
- flat rent amount: **100 economy units**
- grace period: **3 days**
- rent mode: **flat**
- maximum prepaid periods: **0**

The canonical detailed rent flow is in `wiki/docs/players/rent.md`.

The current upstream implementation also contains optional volume-based stall pricing, but the production snapshot does not enable/configure that newer feature. Do not describe dynamic volume pricing as current Enthusia behavior unless a later live snapshot enables it.

## Shops

Current live shop settings include:

- search listings enabled by default,
- Bedrock shop editing enabled,
- 30-day transaction/history retention,
- sale notifications enabled,
- configured shop tax: **2%** to the system destination,
- periodic shop audit every **10 minutes**,
- automatic repair enabled,
- audit work bounded to **5 shops per tick**.

The implementation controls stock/economy transactions rather than relying only on decorative sign text.

## Auctions

Current live auction settings:

- minimum duration: **15 minutes**
- default duration: **24 hours**
- maximum duration: **7 days**
- minimum starting bid: **100**
- anti-snipe trigger window: **30 seconds**
- anti-snipe extension: **30 seconds**
- configured auction fee: **0%**
- direct-buy delay: **0 seconds**

See canonical `wiki/docs/players/auctions.md` for the generic player workflow.

## Guild integration

LumaGuilds integration is enabled and guild market payments are configured to use the **guild bank**.

Guild membership/rank permissions remain authoritative in LumaGuilds; EnthusiaMarket consumes that provider rather than maintaining a second guild identity system.

LumaGuilds' own standalone land-claim feature is currently disabled on Enthusia. That does not disable guild-owned market stalls.

## Schematics and stall reset

Stall schematic snapshot/restore is enabled. The market can restore a stall's baseline as ownership changes rather than permanently inheriting abandoned player modifications.

## Bedrock support

Floodgate-aware forms are implemented and `shop.allowBedrockEdit` is enabled. Player documentation should include Bedrock-equivalent workflows where the interface differs instead of assuming Java inventory/chat UI everywhere.

## Website synchronization

Public market synchronization is currently **enabled**.

Current operational behavior includes:

- startup delay before synchronization,
- short debounce for rapid stall updates,
- full reconciliation every **15 minutes**,
- maximum **1 concurrent request**,
- bounded HTTP timeouts,
- retry/backoff on failures.

The server publishes the intended public market projection through a signed server-side integration. Sync credentials and internal authentication data must never be copied into player/wiki documentation.

## Configuration caveats

Some live config values can look like development placeholders, including the configured `/store` URL in the sanitized snapshot. Do not publish such a value as an intentional public destination unless the actual live command/site behavior is separately verified.

Secrets, purchase-sign trigger tokens and credentials are redacted and are not wiki content.

## Wiki source rule

For an Enthusia public-wiki update:

1. read the relevant canonical `BadgersMC/EnthusiaMarket/wiki/docs/players/` page,
2. verify current values/toggles against the latest `enthusia-server-state` snapshot,
3. preserve `BadgersMC/EnthusiaMarket` as the canonical feature owner,
4. use `enthusia-site` only for the public web presentation layer,
5. do not import historical ItemShops or MarketMapper behavior unless the current Market implementation independently provides it.