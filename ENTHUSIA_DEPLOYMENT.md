# Current Enthusia SMP deployment

The main README describes the broader EnthusiaMarket implementation. This file records the important current production settings visible in the latest `enthusia-server-state` snapshot.

## Status

EnthusiaMarket is present in the current production snapshot and is a **live server system**. Its runtime data directory contains the core market configuration, entity limits and enabled website synchronization.

This `wsg138` repository mirrors/carries code whose documentation still references BadgersMC paths. Future Enthusia wiki tooling should treat the currently deployed EnthusiaMarket behavior/configuration as the player-facing authority, while avoiding edits to separate BadgersMC-owned repositories during this documentation program.

## What the market provides

The current implementation supports:

- WorldGuard-region market stalls,
- player- and guild-owned/rented stalls,
- sign/container item shops,
- searchable shop listings,
- timed auctions and bidding,
- Bedrock/Floodgate form support,
- guild integration through LumaGuilds,
- rent/eviction lifecycle,
- protected stall/shop infrastructure,
- stall schematic snapshot/restore,
- shop history/audit and repair,
- public website synchronization.

## Stall ownership

The production market is bound to the main `world` and recognizes configured stall regions.

Current default stall limit for a player without a special limit permission is **1 stall**. Permission-based limit groups can raise/alter effective limits.

The current deployment also enables IP-based fairness limits:

- one owned stall per IP,
- one active auction bid per IP.

These are anti-abuse/fairness controls and should be described carefully rather than as identity guarantees; shared households can legitimately share an IP.

## Rent

Current production rent settings:

- collection interval: **1 day**
- flat rent amount: **100 economy units**
- grace period: **3 days**
- rent mode: flat
- no configured prepaid-period allowance

The implementation supports configurable rent behavior and eviction. Exact consequences/messages should be taken from the current runtime/language files when a detailed market-rent wiki page is written.

## Sign shops

The market supports buy/sell sign shops linked to inventory/container stock. Current production settings include:

- search listings enabled by default,
- Bedrock shop editing enabled,
- 30-day shop history retention,
- shop notifications enabled,
- 2% configured shop tax to the system destination,
- automatic shop audit enabled every 10 minutes,
- automatic repair enabled,
- audit work bounded to 5 shops per tick.

The implementation protects owner/shop infrastructure and performs trades through controlled/atomic economy+stock flows rather than treating a sign as merely cosmetic text.

## Auctions

Current production auction settings include:

- default duration: **24 hours**
- minimum duration: **15 minutes**
- maximum duration: **7 days**
- minimum starting bid: **100**
- anti-snipe trigger window: **30 seconds**
- anti-snipe extension: **30 seconds**
- configured auction fee: **0%**

Timed auctions are separate from ordinary sign-shop stock listings.

## Guild integration

LumaGuilds integration is enabled and guild market payments are configured to use the guild bank. Guild ownership/permissions should be resolved from LumaGuilds rather than a second market-specific rank-name database.

Remember that LumaGuilds' own land-claim feature is disabled on Enthusia; that does not prevent guild ownership of market stalls through the Market integration.

## Stall reset / schematics

Stall schematic snapshot/restore is enabled. The implementation can preserve/reset a stall's physical baseline as ownership changes rather than allowing abandoned modifications to become permanent market infrastructure damage.

## Bedrock support

Floodgate-aware forms are part of the implementation and `shop.allowBedrockEdit` is enabled. Player documentation should therefore include equivalent Bedrock workflows where the plugin renders a form instead of assuming every interaction uses Java inventory/chat UI.

## Website market

Public website synchronization is currently **enabled**.

The server sends market/stall updates to the Enthusia market API through a signed/shared-secret server-side integration. Current operational behavior includes:

- startup delay before synchronization,
- short debounce for rapid stall changes,
- periodic full reconciliation every **15 minutes**,
- bounded single-request concurrency,
- retry/backoff on failures.

The public website should consume public-safe synchronized market data. It must not receive or expose database credentials or the synchronization secret.

## MarketMapper is not the live market

`EnthusiaMarketMapper` is an offline/experimental physical-market discovery/export tool. Its README explicitly says it does not integrate ownership, shops, prices, stock, Cloudflare or the website.

Future wiki automation must not confuse MarketMapper exports with this live EnthusiaMarket runtime.

## Configuration caveats

The current snapshot still contains some development-looking configuration values, such as the example `/store` URL. Do not publish such values as intentional player-facing policy unless the corresponding live command/site behavior is separately verified.

Secrets and trigger tokens are redacted in the server snapshot and must stay out of public docs.

## Source-of-truth rule

For future wiki generation:

- implementation semantics -> this repository/current deployed code,
- exact production numbers/toggles -> latest `enthusia-server-state` snapshot,
- public market presentation -> `enthusia-site`,
- physical-market discovery/map experimentation -> `EnthusiaMarketMapper` (not authoritative runtime).
