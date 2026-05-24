# Tasks — EnthusiaMarket

**Date:** 2026-05-24
**Status:** Bootstrap (emitted by `/spear:init`; extend via `/spear:spec`)

Tags: `TDD` (failing test before code), `DOC` (markdown / template authoring), `INFRA` (manifests, CI, repo plumbing).
State legend: `[ ]` not started, `[~]` in progress, `[x]` done, `[!]` blocked.

Each task carries `References:` (REQ-IDs + spec sections consulted) and `Evidence:` (sources consulted as work proceeds — an empty block blocks advancement past `spec-done` per REQ-030).

Tasks are ordered to honour state-machine and architectural dependencies. Independent tasks within a milestone may be parallelised.

---

## Milestone M0 — Architectural baseline

### INFRA tasks

- [x] **INFRA-01** — Add GitHub Actions build workflow
  References: REQ-101, tech-stack.md §7
  Tag: INFRA
  Description: Create `.github/workflows/build.yml` running `./gradlew test shadowJar` on push and PR for JDK 21.
  Evidence: ` `

- [x] **INFRA-02** — Wire Konsist dependency for architecture tests
  References: REQ-101, implementation.md §2
  Tag: INFRA
  Description: Add `com.lemonappdev:konsist:0.17.3` testImplementation to `build.gradle.kts`. Confirm `src/test/kotlin/architecture/LayerRulesTest.kt` compiles and passes.
  Evidence:
  ```
  context7:/lemonappdev/konsist-documentation (version + setup)
  jar:com.lemonappdev:konsist:0.17.3 → com/lemonappdev/konsist/api/{Konsist,architecture/{Layer,KoArchitectureCreator}}.class (verified package + signatures)
  build.gradle.kts:46 (testImplementation added)
  src/test/kotlin/architecture/LayerRulesTest.kt (corrected import: com.lemonappdev.konsist.api.architecture.KoArchitectureCreator.assertArchitecture)
  ./gradlew test --tests "net.badgersmc.em.architecture.LayerRulesTest" → BUILD SUCCESSFUL
  ```

- [x] **INFRA-03** — Extend `config.yml` to full spec
  References: REQ-003, REQ-004, REQ-007, REQ-008, REQ-009, REQ-010, REQ-011, docs/config.md
  Tag: INFRA
  Description: Replace `src/main/resources/config.yml` with the full key set in `docs/config.md` (rent, auction, shop, lumaguilds, bedrock, debug sections) including documented defaults. Confirm `Database.open` and `EnthusiaMarket.onEnable` continue to read existing keys without regression.
  Evidence: ` `

- [x] **INFRA-04** — Add permissions block to `plugin.yml`
  References: REQ-022, docs/permissions.md
  Tag: INFRA
  Description: Copy the `permissions:` block from `docs/permissions.md` into `src/main/resources/plugin.yml`. No code wiring yet — perms are checked per-command as they land.
  Evidence: ` `

### DOC tasks

- [x] **DOC-01** — Snapshot pinned external schemas
  References: tech-stack.md §4
  Tag: DOC
  Description: Create `docs/refs/` and snapshot WorldGuard 7.0.9, Vault 1.7, Cumulus 2.0, and LumaGuilds public API surface so breaking upstream changes surface in review.
  Evidence: ` `

---

## Milestone M1 — Shop signs (REQ-005, REQ-006, REQ-020)

### TDD tasks

- [x] **TDD-10** — Persist ShopSign via SignRepositorySql
  References: REQ-005, REQ-020, implementation.md §3.3
  Tag: TDD
  Description: Write a failing test in `src/test/kotlin/net/badgersmc/em/infrastructure/persistence/SignRepositorySqlTest.kt` asserting upsert + findById round-trip against an in-memory SQLite Hikari pool. Run, confirm red.
  Evidence: ` `

- [x] **TDD-11** — Implement SignRepositorySql + migration V002
  References: REQ-005, REQ-020, REQ-042
  Tag: TDD
  Description: Add migration `V002__shop_signs.sql` and `SignRepositorySql` to flip TDD-10 green. No behaviour not asserted by the test.
  Evidence: ` `

- [x] **TDD-12** — Sign placement listener registers sign
  References: REQ-005, implementation.md §3.3
  Tag: TDD
  Description: Failing MockBukkit test placing a shop-format sign inside a stall region triggers `SignRepository.upsert`. Confirm red.
  Evidence: ` `

- [x] **TDD-13** — Implement SignPlaceListener
  References: REQ-005
  Tag: TDD
  Description: Add `infrastructure/listeners/SignPlaceListener` and register in `EnthusiaMarket.onEnable`. Green.
  Evidence: ` `

- [x] **TDD-14** — Atomic shop trade rollback on item failure
  References: REQ-006, REQ-040, REQ-043
  Tag: TDD
  Description: Failing test: buyer with insufficient inventory triggers debit then rollback, leaving balance unchanged. Confirm red.
  Evidence: ` `

- [x] **TDD-15** — Implement ShopTradeService with rollback
  References: REQ-006, REQ-040
  Tag: TDD
  Description: Add `application/ShopTradeService` invoking `EconomyProvider` then item swap, with reverse-on-failure. Green.
  Evidence: ` `

---

## Milestone M2 — Rent collection (REQ-003, REQ-004, REQ-041)

### TDD tasks

- [x] **TDD-20** — Compute rent due per stall
  References: REQ-003, implementation.md §4.2
  Tag: TDD
  Description: Failing unit test on `RentTerms.amountFor(stall)` covering formula and flat-rate variants. Confirm red.
  Evidence: ` `

- [x] **TDD-21** — Extend RentTerms if needed and pass tests
  References: REQ-003
  Tag: TDD
  Description: Minimal change to `domain/stall/RentTerms.kt` to satisfy TDD-20. Green.
  Evidence: ` `

- [x] **TDD-22** — RentCollectionService debits owners
  References: REQ-003, REQ-021
  Tag: TDD
  Description: Failing test: scheduler tick on N rented stalls calls `EconomyProvider.withdraw` N times with correct amounts. Confirm red.
  Evidence: ` `

- [x] **TDD-23** — Implement RentCollectionService
  References: REQ-003
  Tag: TDD
  Description: Add `application/RentCollectionService` and Bukkit-scheduler wiring. Green.
  Evidence: ` `

- [x] **TDD-24** — Default + eviction on insufficient balance
  References: REQ-004
  Tag: TDD
  Description: Failing test: owner balance < rent → stall marked default; after grace period, `Stall.evict()` called and persisted. Confirm red.
  Evidence: ` `

- [x] **TDD-25** — Implement default + grace handling
  References: REQ-004
  Tag: TDD
  Description: Add default/grace logic to `RentCollectionService` and `Stall`. Green.
  Evidence: ` `

- [x] **TDD-26** — Disable services when Vault missing
  References: REQ-041
  Tag: TDD
  Description: Failing test: enabling plugin with no Vault provider skips scheduler registration and logs single error. Confirm red.
  Evidence: ` `

- [x] **TDD-27** — Implement Vault-absent degradation
  References: REQ-041
  Tag: TDD
  Description: Guard rent/auction/sign wiring in `EnthusiaMarket.onEnable`. Green.
  Evidence: ` `

---

## Milestone M3 — Auctions (REQ-007, REQ-008, REQ-009)

### TDD tasks

- [x] **TDD-30** — AuctionRepositorySql round-trip
  References: REQ-007, REQ-020
  Tag: TDD
  Description: Failing test for upsert + findOpen with serialized item. Confirm red.
  Evidence: ` `

- [x] **TDD-31** — Implement AuctionRepositorySql + migration V003
  References: REQ-007, REQ-020, REQ-042
  Tag: TDD
  Description: Add migration and repo to flip TDD-30 green.
  Evidence: ` `

- [x] **TDD-32** — Auction start escrows item
  References: REQ-007
  Tag: TDD
  Description: Failing MockBukkit test: `/em auction start` removes item from inventory and persists auction. Confirm red.
  Evidence: ` `

- [x] **TDD-33** — Implement AuctionLifecycleService.start
  References: REQ-007
  Tag: TDD
  Description: Add service + ACF subcommand. Green.
  Evidence: ` `

- [x] **TDD-34** — Anti-snipe extends end time
  References: REQ-008
  Tag: TDD
  Description: Failing test on `Auction.placeBid` within anti-snipe window extends `endsAt`. Confirm red. (Note: domain `Auction` already has anti-snipe rule scaffolded; verify and harden.)
  Evidence: ` `

- [x] **TDD-35** — Bid command + persistence
  References: REQ-008
  Tag: TDD
  Description: Failing MockBukkit test: `/em bid` validates funds and writes new bid. Confirm red.
  Evidence: ` `

- [x] **TDD-36** — Implement bid command + persistence
  References: REQ-008
  Tag: TDD
  Description: Add `BidCommand` + service call + repository update. Green.
  Evidence: ` `

- [x] **TDD-37** — Auction settlement on expiry
  References: REQ-009, REQ-040
  Tag: TDD
  Description: Failing test: expired auction with winning bid transfers item to bidder, pays seller minus fee, atomically. Confirm red.
  Evidence: ` `

- [x] **TDD-38** — Implement settlement tick
  References: REQ-009
  Tag: TDD
  Description: Add scheduler tick to settle expired auctions with rollback on failure. Green.
  Evidence: ` `

---

## Milestone M4 — Guild ownership + Bedrock UI (REQ-010, REQ-011)

### TDD tasks

- [x] **TDD-40** — Guild stall authorization
  References: REQ-010
  Tag: TDD
  Description: Failing test: guild member with required rank passes `Stall.canManage(actor)`; lower rank fails. Confirm red.
  Evidence: ` `

- [x] **TDD-41** — Replace LumaGuilds stub with real adapter
  References: REQ-010, implementation.md §3.4
  Tag: TDD
  Description: Implement `LumaGuildsGuildProvider` against actual LumaGuilds API. Green.
  Evidence: ` `

- [x] **TDD-42** — Bedrock player gets Cumulus form
  References: REQ-011
  Tag: TDD
  Description: Failing test: stall menu dispatch for Bedrock-tagged UUID invokes Cumulus form sender, not Bukkit GUI. Confirm red.
  Evidence: ` `

- [x] **TDD-43** — Implement Bedrock dispatcher
  References: REQ-011
  Tag: TDD
  Description: Add `infrastructure/bedrock/UiDispatcher` checking `FloodgateApi`. Green.
Evidence: ` `

---

## Milestone M5 — ItemShops port (container-linked GUI shops)

References: REQ-012 through REQ-023

### INFRA tasks

- [x] **INFRA-10** — Add IFramework + Cumulus compile dependencies
  References: REQ-014, REQ-011
  Tag: INFRA
  Description: Add `com.github.stefvanschie.inventoryframework:IF:0.11.6` and `org.geysermc.cumulus:cumulus` as compileOnly deps. Create `Menu` interface + `MenuFactory` routing Java vs Bedrock.
  Evidence: ` `

### TDD tasks

- [x] **TDD-50** — Create shop_items db migration + ShopRepositorySql
  References: REQ-020, docs/tech-stack.md §3
  Tag: TDD
  Description: Failing test for shop_items table round-trip: upsert + findById with sign pos, container pos, owner, sell/cost items (ItemStack serialized), trusted set, hopper flags, frozen flag. Implement V004 migration + ShopRepositorySql.
  Evidence: ` `

- [x] **TDD-51** — Container-linked sign creation via left-click+sneak
  References: REQ-012
  Tag: TDD
  Description: Failing test: player left-clicks wall sign attached to container while sneaking inside owned stall → CreateShopMenu opens. Implement ShopCreateListener. Red before listener exists.
  Evidence: ` `

- [x] **TDD-52** — PurchaseMenu GUI (IFramework, right-click trade)
  References: REQ-013, REQ-014
  Tag: TDD
  Description: Failing test: right-click registered shop sign → PurchaseMenu opens showing item, price, stock. Implement PurchaseMenu (ChestGui) + ShopInteractListener routing.
  Evidence: ` `

- [x] **TDD-53** — Real inventory trade from container
  References: REQ-013, REQ-017
  Tag: TDD
  Description: Failing test: BUY/SELL trade takes item from container inventory (not virtual), updates container on success. Implement ShopTradeService replacement using container items.
  Evidence: ` `

- [x] **TDD-54** — Sign and container break protection
  References: REQ-015
  Tag: TDD
  Description: Failing test: breaking a shop sign → cancelled + owner gets edit menu. Breaking a linked container → all linked shops deleted. Implement BlockProtectionListener.
  Evidence: ` `

- [x] **TDD-55** — Explosion cleanup
  References: REQ-016
  Tag: TDD
  Description: Failing test: EntityExplodeEvent destroys shop containers → shops cleaned up. Implement ExplodeCleanupListener.
  Evidence: ` `

- [x] **TDD-56** — Container stock refresh on inventory change
  References: REQ-017
  Tag: TDD
  Description: Failing test: InventoryClickEvent in linked container → sign text refreshes with trade count. Implement ContainerStockListener.
  Evidence: ` `

- [x] **TDD-57** — Trust management GUI
  References: REQ-018
  Tag: TDD
  Description: Failing test: owner opens trust menu → can add/remove trusted UUIDs. Implement TrustManageMenu.
  Evidence: ` `

- [x] **TDD-58** — Hopper control
  References: REQ-019
  Tag: TDD
  Description: Failing test: hopper attempting to insert/extract from container with hopperAllowIn/Out=false is blocked. Implement HopperControlListener.
  Evidence: ` `

- [x] **TDD-59** — Shop freezing
  References: REQ-023
  Tag: TDD
  Description: Failing test: frozen shop rejects all trades, shows frozen message. Implement freeze toggle in ShopEditMenu.
  Evidence: ` `

- [x] **TDD-60** — Bedrock Cumulus menus for create/purchase/edit
  References: REQ-011, REQ-012, REQ-013
  Tag: TDD
  Description: Failing test: Bedrock player creates/purchases/edits shop → Cumulus form used. Implement BedrockCreateShopMenu, BedrockPurchaseMenu, BedrockShopEditMenu extending BaseBedrockMenu.
  Evidence: ` `

---

## Milestone M6 — Guild integration & event emission

References: REQ-024 through REQ-027

### TDD tasks

- [ ] **TDD-70** — Add guild_id + creator_id to shop_items (V005 migration)
  References: REQ-024, docs/db-schema.md
  Tag: TDD
  Description: Failing test: V005 migration adds guild_id (VARCHAR(36), nullable) and creator_id (VARCHAR(36), nullable) columns to shop_items. ShopRepositorySql.findById returns Shop with guildId/creatorId populated. Confirm red.
  Evidence: ` `

- [ ] **TDD-71** — ShopRepositorySql guild queries
  References: REQ-024
  Tag: TDD
  Description: Failing test: `findByGuildId(guildId)` returns all shops owned by a guild; `setGuildOwnership(shopId, guildId, creatorId)` updates guild_id and creator_id; `removeGuildOwnership(shopId)` clears them. Confirm red.
  Evidence: ` `

- [ ] **TDD-72** — Fire ShopCreatedEvent and ShopDeletedEvent
  References: REQ-026
  Tag: TDD
  Description: Failing test: ShopCreateListener creates shop → Bukkit event `net.badgersmc.em.events.ShopCreatedEvent` fired with owner UUID. Shop deletion (break, inventory break) → `ShopDeletedEvent` fired. Confirm red. Event classes in `net.badgersmc.em.events` package with matching fields.
  Evidence: ` `

- [ ] **TDD-73** — Fire PostShopTransactionEvent
  References: REQ-027
  Tag: TDD
  Description: Failing test: ContainerTradeService executes BUY trade → Bukkit event `net.badgersmc.em.events.PostShopTransactionEvent` fired with buyer Player, landlordId UUID, item ItemStack, quantity int, pricePaid double. Confirm red.
  Evidence: ` `

- [ ] **TDD-74** — Fire ShopStockDepletedEvent
  References: REQ-026
  Tag: TDD
  Description: Failing test: ContainerStockListener detects stock reached 0 in linked inventory → Bukkit event `net.badgersmc.em.events.ShopStockDepletedEvent` fired with owner UUID. Confirm red.
  Evidence: ` `

- [ ] **TDD-75** — Guild income routing in ContainerTradeService
  References: REQ-025
  Tag: TDD
  Description: Failing test: ContainerTradeService completes BUY on guild-owned shop → Vault withdraw from buyer, deposit to GuildVaultService (not player Vault). Player-owned shop still routes to player Vault. Confirm red. GuildVaultService is a provided interface via LumaGuildsHook.
  Evidence: ` `

- [ ] **TDD-76** — Shop guild registration service
  References: REQ-024
  Tag: TDD
  Description: Failing test: `ShopGuildService.registerGuildShop(shopId, guildId, playerId)` sets guild ownership and returns updated Shop. Duplicate registration returns failure. Confirm red.
  Evidence: ` `

### INFRA tasks

- [ ] **INFRA-11** — EM event classes for external integration
  References: REQ-026, REQ-027
  Tag: INFRA
  Description: Create Bukkit event classes under `net.badgersmc.em.events`: ShopCreatedEvent(UUID ownerId), ShopDeletedEvent(UUID ownerId), ShopStockDepletedEvent(UUID ownerId), PostShopTransactionEvent(Player buyer, UUID landlordId, ItemStack item, int quantity, double pricePaid). All extend Event with static HandlerList.
  Evidence: ` `

---

## Task authoring rules

1. Every task has exactly ONE tag (`TDD`, `DOC`, or `INFRA`).
2. `References:` cites at least one REQ-ID from `requirements.md`. If the REQ doesn't exist, run `/spear:spec` first.
3. `Evidence:` starts empty (`\ \``). It must be filled before any skill past `spec-done` will run (REQ-030). Each line is a verified source (e.g. `context7:react@19/useEffect`, `src/domain/Order.kt:42`, `docs/implementation.md#3.1`).
4. Task size ceiling: ~1500 tokens of full briefing. If larger, split.
5. A task MUST be achievable by a single SPEAR cycle (`spec → prove → engine → arch → refine` for TDD; `spec → arch → refine` for DOC/INFRA).
6. Mark state as work proceeds: `[~]` when entering `spec`; `[x]` only when `/spear:refine` has cleared state to `idle`.
