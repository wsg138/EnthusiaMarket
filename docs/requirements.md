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

## Interfaces & contracts

### REQ-020 — Persistence backend
**Ubiquitous.** THE SYSTEM SHALL persist all stall, sign, and auction state to a JDBC datasource configured as either SQLite (default) or MariaDB.

### REQ-021 — Vault economy contract
**Ubiquitous.** THE SYSTEM SHALL route all currency debits and credits through the Vault Economy provider with no direct balance mutation elsewhere.

### REQ-022 — Admin command surface
**Ubiquitous.** THE SYSTEM SHALL expose administrative subcommands under `/enthusiamarket` (alias `/em`) gated by the `enthusiamarket.admin` permission.

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
