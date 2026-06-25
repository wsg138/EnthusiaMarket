
# Requirements — EnthusiaMarket

**Date:** 2026-05-24
**Status:** Bootstrap (emitted by `/spear:init`; extend via `/spear:spec`)
**EARS subset enforced:** Ubiquitous, Event-driven, State-driven, Unwanted. Optional Feature pattern (`WHERE …`) accepted without validation.

Each requirement carries a stable ID. Tasks reference requirements by ID. New requirements append at the next free integer ID (three-digit padded); IDs are never re-used or renumbered.

---

## Product (what the system is for)

### REQ-001 — Stall marketplace as core product

**Ubiquitous.** THE SYSTEM SHALL expose WorldGuard regions as rentable or ownable market stalls for individual players and guilds.

### REQ-002 — Admin region import

**Event-driven.** WHEN an operator runs the admin import command THE SYSTEM SHALL idempotently register every WorldGuard region matching the configured world and prefix as a stall.

### REQ-003 — Rent collection

**Event-driven.** WHEN the configured rent collection interval elapses THE SYSTEM SHALL debit each rented stall's owner balance by the computed rent amount.

### REQ-004 — Default eviction

**Unwanted.** IF a stall owner balance is insufficient to cover rent at collection time THEN THE SYSTEM SHALL mark the stall as in default and evict the owner after the grace period configured for that stall.

> **Backlog (REQ-280, post-release):** the grace-expiry path is intended to fire an *emergency
> auction* for the stall rather than evict directly to UNOWNED. Not built for the current
> release — see REQ-280.

### REQ-005 — Sign shop creation (superseded 2026-05-24 by REQ-012)

~~**Event-driven.** WHEN a player places a shop sign inside a stall they own or rent THE SYSTEM SHALL register the sign as a buy or sell endpoint scoped to that stall region.~~
**Note:** Replaced by container-linked wall sign creation (REQ-012).

### REQ-006 — Shop sign transaction (superseded 2026-05-24 by REQ-013)

~~**Event-driven.** WHEN a player interacts with a registered shop sign with a valid item and balance THE SYSTEM SHALL transfer the item and the price atomically between buyer and seller.~~
**Note:** Replaced by GUI-based shop trade with container inventory (REQ-013).

### REQ-007 — Auction creation

**Event-driven.** WHEN a player runs the auction create command with a held item and a valid duration THE SYSTEM SHALL escrow the item and open an auction lot for that duration.

### REQ-008 — Anti-snipe bid extension

**Event-driven.** WHEN a winning bid is placed within the anti-snipe window before an auction closes THE SYSTEM SHALL extend the auction end time by the configured anti-snipe duration.

### REQ-009 — Auction settlement

**Event-driven.** WHEN an auction reaches its end time without further bids THE SYSTEM SHALL transfer the escrowed item to the high bidder and pay the seller the winning amount minus the configured fee.

### REQ-010 — Guild stall ownership

**State-driven.** WHILE a stall is owned by a guild THE SYSTEM SHALL authorize all guild members with the configured rank to manage the stall's signs and auctions.

### REQ-011 — Bedrock player UI

**Event-driven.** WHEN a Bedrock player opens a stall or auction menu THE SYSTEM SHALL render the interaction as a Cumulus form instead of a Java inventory GUI.

### REQ-012 — Container-linked sign creation

**Event-driven.** WHEN a player left-clicks a wall sign attached to a container block while sneaking within a stall they own or rent THE SYSTEM SHALL open a creation GUI to register the sign and link it to the container as a buy or sell endpoint.

### REQ-013 — Shop trade via GUI

**Event-driven.** WHEN a player right-clicks a registered shop sign THE SYSTEM SHALL open a trade GUI showing the item, price, and stock, and execute the transaction from the linked container's real inventory.

### REQ-014 — IFramework GUI rendering

**Ubiquitous.** THE SYSTEM SHALL render all Java player shop menus using the IFramework library (com.github.stefvanschie.inventoryframework:IF:0.11.6).

### REQ-015 — Sign and container destruction

**Unwanted.** IF a player attempts to break a sign linked to a shop or a container linked to a shop THEN THE SYSTEM SHALL cancel the break and either open the edit menu (for owners) or notify the player (for non-owners).

### REQ-016 — Explosion cleanup

**Event-driven.** WHEN an explosion destroys a container or sign linked to a shop THE SYSTEM SHALL delete the shop record to prevent orphaned data.

### REQ-017 — Container stock tracking

**Event-driven.** WHEN the inventory of a linked container changes THE SYSTEM SHALL update the shop sign's visible stock count.

### REQ-018 — Shop trust system

**Ubiquitous.** THE SYSTEM SHALL allow shop owners to add and remove trusted players who can edit the shop's item and price without owning the stall.

### REQ-019 — Hopper control

**Ubiquitous.** THE SYSTEM SHALL allow per-shop configuration of hopper input and output into the linked container.

---

### REQ-023 — Shop freezing

**Ubiquitous.** THE SYSTEM SHALL allow shop owners and admins to freeze a shop, preventing all trades until unfrozen.

### REQ-024 — Guild-owned shop creation via command

**Event-driven.** WHEN a guild member with MANAGE_GUILD_SETTINGS runs `/guild setshop` while targeting a valid EM shop container THE SYSTEM SHALL register the shop as guild-owned by storing the guild ID and player creator ID.

### REQ-025 — Guild shop income routing

**Event-driven.** WHEN a trade completes at a guild-owned shop in PHYSICAL or BOTH bank mode THE SYSTEM SHALL deposit the proceeds into the guild vault service instead of the player's Vault economy balance.

### REQ-026 — Shop lifecycle Bukkit events

**Event-driven.** WHEN a shop is created, deleted, or its stock is depleted THE SYSTEM SHALL fire a corresponding Bukkit event with the shop owner UUID for external plugin consumption.

### REQ-027 — Shop transaction Bukkit events

**Event-driven.** WHEN a shop transaction completes THE SYSTEM SHALL fire a Bukkit PostShopTransactionEvent containing buyer, seller ID, item, quantity, and price.

---

## Interfaces & contracts

### REQ-020 — Persistence backend

**Ubiquitous.** THE SYSTEM SHALL persist all stall, sign, and auction state to a JDBC datasource configured as either SQLite (default) or MariaDB.

### REQ-021 — Vault economy contract

**Ubiquitous.** THE SYSTEM SHALL route all currency debits and credits through the Vault Economy provider with no direct balance mutation elsewhere.

### REQ-022 — Admin command surface

**Ubiquitous.** THE SYSTEM SHALL expose administrative subcommands under `/enthusiamarket` (alias `/em`) gated by the `enthusiamarket.admin` permission.

### REQ-028 — Mass auction launch

**Event-driven.** WHEN an administrator executes `/em auction startall <startingBid> [duration]` THE SYSTEM SHALL create a single open auction with the same starting bid and end time for every stall currently in the `UNOWNED` state, transition each affected stall to `AUCTIONING`, skip stalls that already have an open auction, and report the counts of created, skipped, and errored stalls.

### REQ-029 — Live auction browser menu

**Event-driven.** WHEN a player executes `/em auctions` THE SYSTEM SHALL open a paginated GUI listing every open auction with its stall id, current high bid, time remaining, and a sort toggle cycling through `HIGHEST_BID`, `LOWEST_BID`, `ENDING_SOON`, and `ENDING_LATEST`, refreshing its contents at least once per second while the menu remains open.

### REQ-283 — Shop search item tab-completion

**Event-driven.** WHEN a player tab-completes the item argument of `/shop search` THE SYSTEM SHALL suggest the names of item materials whose name starts with the typed prefix, case-insensitively.

### REQ-284 — Default stall ownership limit

**State-driven.** WHILE a player holds no `enthusiamarket.limit.<group>` permission THE SYSTEM SHALL apply the configured default stall limit rather than treating the player as having unlimited stalls.

### REQ-285 — Stall region piston protection

**Event-driven.** WHEN a stall region is provisioned THE SYSTEM SHALL deny piston block movement in the region so blocks and items cannot be pushed across the stall boundary.

### REQ-286 — Rent pre-payment cap

**Unwanted.** IF a player attempts to extend a stall's rent beyond the configured maximum number of pre-paid periods ahead THEN THE SYSTEM SHALL reject the extension.

### REQ-287 — Live rent countdown on signs

**State-driven.** WHILE a stall is OWNED or in GRACE THE SYSTEM SHALL re-render its purchase sign on a fixed interval so the displayed rent countdown stays current.

### REQ-288 — Stall identifier display

**Event-driven.** WHEN a stall's purchase confirmation or info card is shown THE SYSTEM SHALL display the stall's identifier so players can tell stalls apart.

> **REQ-ID note:** 281/282 reserved by `perf/hopper-shop-index` (PR #61) and 283 by `feat/shop-search-tab-complete` (PR #62); this batch uses 284–288 to avoid merge collisions. Flat-rent ($/period) is a config change to REQ-003, no new REQ.

---

## Non-functional

### REQ-040 — Atomic economy operations

**Unwanted.** IF an item transfer fails during a shop or auction settlement THEN THE SYSTEM SHALL roll back the corresponding economy operation within the same transaction boundary.

### REQ-041 — Vault unavailable degradation

**Unwanted.** IF the Vault economy provider is unavailable at plugin enable THEN THE SYSTEM SHALL disable rent collection, sign shops, and auctions and log a single startup error.

### REQ-042 — Migration idempotency

**Ubiquitous.** THE SYSTEM SHALL apply database migrations in versioned order and skip any migration whose version is already recorded.

### REQ-043 — Server thread safety

**Ubiquitous.** THE SYSTEM SHALL execute all Bukkit world, inventory, and entity mutations on the main server thread.

### REQ-281 — Hopper control without per-event database I/O

**Event-driven.** WHEN an InventoryMoveItemEvent fires THE SYSTEM SHALL resolve the shop status and hopper permissions of the source and destination containers from an in-memory index without querying the database on the server thread.

### REQ-282 — Shop container index reflects persisted state

**Ubiquitous.** THE SYSTEM SHALL maintain an in-memory shop container index that reflects every persisted shop container location, rebuilt from persistent storage on plugin enable and updated whenever a shop is created, updated, or deleted.

---

## ARM-inspired feature set (Phase 5)

Reference: study of `advanced-region-market` (ARM) plugin. Eight features selected for adoption: member system, limit groups, entity limits, region info, particle border, sign click actions, sell offers, schematic-based reset.

### Member system

#### REQ-200 — Stall member roster

**Ubiquitous.** THE SYSTEM SHALL maintain a per-stall member list (set of player UUIDs) distinct from the owner, persisted alongside the stall record.

#### REQ-201 — Member cap by region kind

**Ubiquitous.** THE SYSTEM SHALL enforce a configurable `maxMembers` value per stall (-1 = unlimited) and reject member additions that would exceed it.

#### REQ-202 — Member commands

**Event-driven.** WHEN the owner executes `/em stall members add|remove|list <player>` THE SYSTEM SHALL mutate the roster, sync the change to the underlying WorldGuard region's member list, and persist.

#### REQ-203 — Member permission inheritance

**State-driven.** WHILE a player is a member of a stall THE SYSTEM SHALL grant them WorldGuard build/interact permissions inside the stall region (delegated to WorldGuard via region member assignment).

### Limit groups

#### REQ-210 — Limit group config block

**Ubiquitous.** THE SYSTEM SHALL load named limit groups from `enthusiamarket.yaml` under `limits.<group-name>` with fields `total: Int` and `regionkinds: Map<String, Int>` where -1 means unlimited.

#### REQ-211 — Limit group assignment via permission

**Ubiquitous.** THE SYSTEM SHALL resolve a player's effective limit by intersecting every limit group whose permission node (`enthusiamarket.limit.<group-name>`) the player holds and taking the best value per dimension.

#### REQ-212 — Limit enforcement on stall acquisition

**Unwanted.** IF a player would exceed their effective total or kind-specific limit by acquiring a stall THE SYSTEM SHALL reject the acquisition with a translated lang message.

#### REQ-213 — Admin bypass

**Ubiquitous.** THE SYSTEM SHALL grant unlimited stall ownership to any player holding `enthusiamarket.admin.bypasslimit`.

### Entity limits

#### REQ-220 — Entity limit groups by region kind

**Ubiquitous.** THE SYSTEM SHALL associate each region kind with an entity limit group declaring per-entity caps (e.g. `villager: 5`, `armor_stand: 10`, `_total: 50`).

#### REQ-221 — Entity limit enforcement

**Unwanted.** IF a mob/entity spawn or item-frame placement inside a stall would exceed the active entity limit group's per-type or total cap THE SYSTEM SHALL cancel the event.

#### REQ-222 — Per-stall entity-limit override

**Optional.** THE SYSTEM SHALL allow admins to grant individual stalls extra entity allowance (`extraEntities[<type>]`, `extraTotal`) via `/em stall entitylimit set`.

### Region info command

#### REQ-230 — Player-facing region info

**Event-driven.** WHEN a player executes `/em stall info <stall>` THE SYSTEM SHALL send the player a translated info card showing: stall id, region kind, owner, member count, current rent, time until next rent collection, dimensions in blocks, state, and whether the stall is currently available to claim.

#### REQ-231 — Region info from sign right-click

**Event-driven.** WHEN a player right-clicks a stall purchase sign while not currently the owner THE SYSTEM SHALL invoke the same info card displayed by REQ-230.

### Particle border preview

#### REQ-240 — Particle outline on stall selection

**Event-driven.** WHEN a player executes `/em stall outline <stall>` THE SYSTEM SHALL render a particle border tracing the stall's WorldGuard region for a configurable duration (default 10s) visible only to that player.

#### REQ-241 — Particle outline performance bound

**Ubiquitous.** THE SYSTEM SHALL emit at most `particles.maxPerTick` particles per server tick across all active outlines (default 200) and degrade by reducing particle density rather than dropping outlines entirely.

### Sign click actions

#### REQ-250 — Stall purchase signs

**Ubiquitous.** THE SYSTEM SHALL support placing physical signs in the world linked to a stall such that right-clicking the sign triggers a claim/extend/buy action appropriate to the stall's state, and the sign's text is auto-rendered from a translated template.

#### REQ-251 — Sign creation workflow

**Event-driven.** WHEN an admin (or stall owner where permitted) creates a sign whose first line matches the configured trigger token (e.g. `[em]`) and whose second line names an existing stall THE SYSTEM SHALL register the sign as a linked purchase sign, sync its text on creation, and persist the binding.

#### REQ-252 — Sign auto-refresh on state change

**Event-driven.** WHEN a stall's owner, price, or remaining-rent state changes THE SYSTEM SHALL re-render every linked sign for that stall within one tick.

#### REQ-253 — Sign destruction tracking

**Event-driven.** WHEN a linked purchase sign is broken THE SYSTEM SHALL remove the persisted binding and emit a Bukkit event so other plugins can react.

### Sell offers

#### REQ-260 — Owner-listed sell offers

**Event-driven.** WHEN a stall owner executes `/em stall offer <price>` THE SYSTEM SHALL create a public offer to transfer ownership of the stall to any buyer who pays the listed price plus the configured tax percentage.

#### REQ-261 — Offer acceptance

**Event-driven.** WHEN a player executes `/em stall buy <stall>` against a stall with an open offer THE SYSTEM SHALL withdraw `price * (1 + tax)` from the buyer, deposit `price` to the seller, deposit `price * tax` to the system sink (or configured account), reassign stall ownership, and close the offer atomically.

#### REQ-262 — Offer cancellation

**Event-driven.** WHEN the owning seller executes `/em stall offer cancel` THE SYSTEM SHALL close the open offer without transferring ownership.

#### REQ-263 — Offer mutual exclusion with auction

**Unwanted.** IF a stall has an open auction THE SYSTEM SHALL reject any attempt to create a sell offer on it, and vice versa.

#### REQ-264 — Offer tax routing

**Ubiquitous.** THE SYSTEM SHALL apply the same `shop.taxPct` config value to offer transactions as to shop trades, and route the tax to the same destination configured for shop tax.

### Schematic-based reset

#### REQ-270 — Stall schematic snapshot on claim

**Event-driven.** WHEN a stall transitions from `UNOWNED` to any `OWNED`/`AUCTIONING`/`RENTED` state for the first time THE SYSTEM SHALL save a WorldEdit schematic of the stall's WorldGuard region to `plugins/EnthusiaMarket/schematics/<stallId>.schem` before ownership is finalised.

#### REQ-271 — Schematic restore on unclaim

**Event-driven.** WHEN a stall transitions back to `UNOWNED` (eviction, owner-sell, lease expiry, admin reset) THE SYSTEM SHALL paste the stored schematic over the stall region, restoring the original geometry before the stall is marked available.

#### REQ-272 — WorldEdit/FAWE compatibility

**Ubiquitous.** THE SYSTEM SHALL use FastAsyncWorldEdit's API when the `FastAsyncWorldEdit` plugin is loaded and fall back to vanilla WorldEdit otherwise, performing pastes asynchronously when FAWE is available.

#### REQ-273 — Snapshot disabled fallback

**Optional.** THE SYSTEM SHALL allow operators to disable schematic snapshots via `schematics.enabled: false`, in which case stall transitions occur without geometry capture or restore.

#### REQ-274 — Snapshot failure handling

**Unwanted.** IF a snapshot capture fails THE SYSTEM SHALL log the failure, abort the ownership transition, refund any economy charge incurred during the attempt, and emit a Bukkit event so operators can be notified.

---

## Post-release backlog (DRAFT — not in current release)

#### REQ-280 — Emergency auction on grace expiry (DRAFT, post-release)

**Status:** DRAFT — captured 2026-06-01, deferred from the release. Refines REQ-004.

**Event-driven.** WHEN a stall's grace period for unpaid rent expires THE SYSTEM SHALL,
instead of evicting the stall directly to UNOWNED, transition it to `EMERGENCY_AUCTIONING`
and open a system auction for its ownership. WHEN that auction settles with a high bidder
THE SYSTEM SHALL award the stall to the bidder; WHEN it closes with no bids THE SYSTEM SHALL
return the stall to UNOWNED.

**Notes / open design questions (resolve in brainstorming before building):**
- Reset timing: the geometry snapshot restore (REQ-271) should fire **when the emergency
  auction is created** (the defaulter's build is wiped immediately and the stall is auctioned
  clean) — confirmed intent 2026-06-01. Confirm whether a no-bid close needs a second reset.
- The `EMERGENCY_AUCTIONING` and `RE_AUCTIONING` `StallState` enum values already exist and
  are handled defensively in sign rendering / buyout rejection / rg-resync, but **nothing
  currently transitions a stall into them** — this REQ wires the missing transition.
- Current release behaviour (REQ-004): grace expiry evicts directly to UNOWNED + immediate
  schematic restore in `RentCollectionService.processStall`. REQ-280 supersedes that path.
- Distinguish `RE_AUCTIONING` (re-auction after a normal owner relinquish?) from
  `EMERGENCY_AUCTIONING` (forced, non-payment) when specced.

---

### REQ-289 — Unified shop creation GUI

**Event-driven.** WHEN a player places a wall sign on a container inside a stall they own or rent THE SYSTEM SHALL scan the container for the sellable item, open a creation GUI that collects: trade direction (SELL/BUY/TRADE), per-trade amount, and cost configuration (Vault currency amount OR barter item type+amount from the player's hand), and write the sign text only after the player confirms in the GUI.

**Note:** Supersedes REQ-012 §1 (which said "open a creation GUI… as a buy or sell endpoint" but the GUI never offered the choice). The underlying stall/container/auth checks from REQ-012 §2+ are unchanged.

### REQ-290 — Normalize shop sign text parsing

**Ubiquitous.** THE SYSTEM SHALL parse the direction token on shop sign line 1 case-insensitively (accepting `[buy]`, `[BUY]`, `[Buy]` as equivalent) and render the sign using readable direction text rather than the raw enum name.

*Supersedes REQ-005 (sign-text parsing created the shop directly; the new flow scans the container and opens the REQ-289 GUI with the detected item).*

### REQ-291 — PurchaseMenu shows full shop context

**Ubiquitous.** THE SYSTEM SHALL render the trade GUI with: the sell item icon + per-trade amount, the cost item/currency icon + amount, a direction label ("Buying from shop"/"Selling to shop"/"Trading"), the remaining trades-available from live container stock, and the shop owner's name.

### REQ-292 — OwnedShopsMenu shows shop status at a glance

**Ubiquitous.** THE SYSTEM SHALL display each owned shop's direction label, stock count, and frozen status in its icon lore within the owned-shops listing.

### REQ-293 — ShopEditMenu shows stock count

**Ubiquitous.** THE SYSTEM SHALL display the shop's current container stock count and direction in the edit GUI.

### REQ-294 — SearchResultsMenu shows direction

**Ubiquitous.** THE SYSTEM SHALL include the shop's trade direction in each search result's lore.

### REQ-295 — Normalize GUI text styling

**Ubiquitous.** THE SYSTEM SHALL render all menu item names and lore in normal-weight, non-italic text. Color SHALL be reserved for semantic categories: green for confirm/action/create, red for danger/delete/cancel, gold for currency/price, white for neutral item info, gray for secondary labels, aqua for navigation, yellow for warnings, and light-purple for barter/TRADE indicators. Bold or italic styling SHALL NOT be used in menu text.

---

## Acceptance

### REQ-100 — Smoke test on MockBukkit

**Event-driven.** WHEN the plugin is loaded into MockBukkit with default config THE SYSTEM SHALL enable without throwing and register the `enthusiamarket` command.

### REQ-101 — Konsist layer enforcement

**Ubiquitous.** THE SYSTEM SHALL pass the Konsist architecture test asserting domain has no outward dependencies and application depends only on domain.

---

## Authoring rules

1. Every REQ has a single ID, a heading, and exactly one EARS-formatted sentence under a **pattern label** (Ubiquitous / Event-driven / State-driven / Unwanted / Optional).
2. Use `/spear:spec` to add or revise REQ entries — it runs the EARS validator (`plugins/spear/hooks/lib/ears.mjs`) and assigns the next free ID.
3. Never reuse an ID. When a requirement is obsolete, strike it through and note the deprecation date; do not renumber.
