
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
  Evidence: ``

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
  Evidence: ``

- [x] **INFRA-04** — Add permissions block to `plugin.yml`
  References: REQ-022, docs/permissions.md
  Tag: INFRA
  Description: Copy the `permissions:` block from `docs/permissions.md` into `src/main/resources/plugin.yml`. No code wiring yet — perms are checked per-command as they land.
  Evidence: ``

### DOC tasks

- [x] **DOC-01** — Snapshot pinned external schemas
  References: tech-stack.md §4
  Tag: DOC
  Description: Create `docs/refs/` and snapshot WorldGuard 7.0.9, Vault 1.7, Cumulus 2.0, and LumaGuilds public API surface so breaking upstream changes surface in review.
  Evidence: ``

---

## Milestone M1 — Shop signs (REQ-005, REQ-006, REQ-020)

### TDD tasks

- [x] **TDD-10** — Persist ShopSign via SignRepositorySql
  References: REQ-005, REQ-020, implementation.md §3.3
  Tag: TDD
  Description: Write a failing test in `src/test/kotlin/net/badgersmc/em/infrastructure/persistence/SignRepositorySqlTest.kt` asserting upsert + findById round-trip against an in-memory SQLite Hikari pool. Run, confirm red.
  Evidence: ``

- [x] **TDD-11** — Implement SignRepositorySql + migration V002
  References: REQ-005, REQ-020, REQ-042
  Tag: TDD
  Description: Add migration `V002__shop_signs.sql` and `SignRepositorySql` to flip TDD-10 green. No behaviour not asserted by the test.
  Evidence: ``

- [x] **TDD-12** — Sign placement listener registers sign
  References: REQ-005, implementation.md §3.3
  Tag: TDD
  Description: Failing MockBukkit test placing a shop-format sign inside a stall region triggers `SignRepository.upsert`. Confirm red.
  Evidence: ``

- [x] **TDD-13** — Implement SignPlaceListener
  References: REQ-005
  Tag: TDD
  Description: Add `infrastructure/listeners/SignPlaceListener` and register in `EnthusiaMarket.onEnable`. Green.
  Evidence: ``

- [x] **TDD-14** — Atomic shop trade rollback on item failure
  References: REQ-006, REQ-040, REQ-043
  Tag: TDD
  Description: Failing test: buyer with insufficient inventory triggers debit then rollback, leaving balance unchanged. Confirm red.
  Evidence: ``

- [x] **TDD-15** — Implement ShopTradeService with rollback
  References: REQ-006, REQ-040
  Tag: TDD
  Description: Add `application/ShopTradeService` invoking `EconomyProvider` then item swap, with reverse-on-failure. Green.
  Evidence: ``

---

## Milestone M2 — Rent collection (REQ-003, REQ-004, REQ-041)

### TDD tasks

- [x] **TDD-20** — Compute rent due per stall
  References: REQ-003, implementation.md §4.2
  Tag: TDD
  Description: Failing unit test on `RentTerms.amountFor(stall)` covering formula and flat-rate variants. Confirm red.
  Evidence: ``

- [x] **TDD-21** — Extend RentTerms if needed and pass tests
  References: REQ-003
  Tag: TDD
  Description: Minimal change to `domain/stall/RentTerms.kt` to satisfy TDD-20. Green.
  Evidence: ``

- [x] **TDD-22** — RentCollectionService debits owners
  References: REQ-003, REQ-021
  Tag: TDD
  Description: Failing test: scheduler tick on N rented stalls calls `EconomyProvider.withdraw` N times with correct amounts. Confirm red.
  Evidence: ``

- [x] **TDD-23** — Implement RentCollectionService
  References: REQ-003
  Tag: TDD
  Description: Add `application/RentCollectionService` and Bukkit-scheduler wiring. Green.
  Evidence: ``

- [x] **TDD-24** — Default + eviction on insufficient balance
  References: REQ-004
  Tag: TDD
  Description: Failing test: owner balance < rent → stall marked default; after grace period, `Stall.evict()` called and persisted. Confirm red.
  Evidence: ``

- [x] **TDD-25** — Implement default + grace handling
  References: REQ-004
  Tag: TDD
  Description: Add default/grace logic to `RentCollectionService` and `Stall`. Green.
  Evidence: ``

- [x] **TDD-26** — Disable services when Vault missing
  References: REQ-041
  Tag: TDD
  Description: Failing test: enabling plugin with no Vault provider skips scheduler registration and logs single error. Confirm red.
  Evidence: ``

- [x] **TDD-27** — Implement Vault-absent degradation
  References: REQ-041
  Tag: TDD
  Description: Guard rent/auction/sign wiring in `EnthusiaMarket.onEnable`. Green.
  Evidence: ``

---

## Milestone M3 — Auctions (REQ-007, REQ-008, REQ-009)

### TDD tasks

- [x] **TDD-30** — AuctionRepositorySql round-trip
  References: REQ-007, REQ-020
  Tag: TDD
  Description: Failing test for upsert + findOpen with serialized item. Confirm red.
  Evidence: ``

- [x] **TDD-31** — Implement AuctionRepositorySql + migration V003
  References: REQ-007, REQ-020, REQ-042
  Tag: TDD
  Description: Add migration and repo to flip TDD-30 green.
  Evidence: ``

- [x] **TDD-32** — Auction start escrows item
  References: REQ-007
  Tag: TDD
  Description: Failing MockBukkit test: `/em auction start` removes item from inventory and persists auction. Confirm red.
  Evidence: ``

- [x] **TDD-33** — Implement AuctionLifecycleService.start
  References: REQ-007
  Tag: TDD
  Description: Add service + ACF subcommand. Green.
  Evidence: ``

- [x] **TDD-34** — Anti-snipe extends end time
  References: REQ-008
  Tag: TDD
  Description: Failing test on `Auction.placeBid` within anti-snipe window extends `endsAt`. Confirm red. (Note: domain `Auction` already has anti-snipe rule scaffolded; verify and harden.)
  Evidence: ``

- [x] **TDD-35** — Bid command + persistence
  References: REQ-008
  Tag: TDD
  Description: Failing MockBukkit test: `/em bid` validates funds and writes new bid. Confirm red.
  Evidence: ``

- [x] **TDD-36** — Implement bid command + persistence
  References: REQ-008
  Tag: TDD
  Description: Add `BidCommand` + service call + repository update. Green.
  Evidence: ``

- [x] **TDD-37** — Auction settlement on expiry
  References: REQ-009, REQ-040
  Tag: TDD
  Description: Failing test: expired auction with winning bid transfers item to bidder, pays seller minus fee, atomically. Confirm red.
  Evidence: ``

- [x] **TDD-38** — Implement settlement tick
  References: REQ-009
  Tag: TDD
  Description: Add scheduler tick to settle expired auctions with rollback on failure. Green.
  Evidence: ``

---

## Milestone M4 — Guild ownership + Bedrock UI (REQ-010, REQ-011)

### TDD tasks

- [x] **TDD-40** — Guild stall authorization
  References: REQ-010
  Tag: TDD
  Description: Failing test: guild member with required rank passes `Stall.canManage(actor)`; lower rank fails. Confirm red.
  Evidence: ``

- [x] **TDD-41** — Replace LumaGuilds stub with real adapter
  References: REQ-010, implementation.md §3.4
  Tag: TDD
  Description: Implement `LumaGuildsGuildProvider` against actual LumaGuilds API. Green.
  Evidence: ``

- [x] **TDD-42** — Bedrock player gets Cumulus form
  References: REQ-011
  Tag: TDD
  Description: Failing test: stall menu dispatch for Bedrock-tagged UUID invokes Cumulus form sender, not Bukkit GUI. Confirm red.
  Evidence: ``

- [x] **TDD-43** — Implement Bedrock dispatcher
  References: REQ-011
  Tag: TDD
  Description: Add `infrastructure/bedrock/UiDispatcher` checking `FloodgateApi`. Green.
Evidence: ``

---

## Milestone M5 — ItemShops port (container-linked GUI shops)

References: REQ-012 through REQ-023

### INFRA tasks

- [x] **INFRA-10** — Add IFramework + Cumulus compile dependencies
  References: REQ-014, REQ-011
  Tag: INFRA
  Description: Add `com.github.stefvanschie.inventoryframework:IF:0.11.6` and `org.geysermc.cumulus:cumulus` as compileOnly deps. Create `Menu` interface + `MenuFactory` routing Java vs Bedrock.
  Evidence: ``

### TDD tasks

- [x] **TDD-50** — Create shop_items db migration + ShopRepositorySql
  References: REQ-020, docs/tech-stack.md §3
  Tag: TDD
  Description: Failing test for shop_items table round-trip: upsert + findById with sign pos, container pos, owner, sell/cost items (ItemStack serialized), trusted set, hopper flags, frozen flag. Implement V004 migration + ShopRepositorySql.
  Evidence: ``

- [x] **TDD-51** — Container-linked sign creation via left-click+sneak
  References: REQ-012
  Tag: TDD
  Description: Failing test: player left-clicks wall sign attached to container while sneaking inside owned stall → CreateShopMenu opens. Implement ShopCreateListener. Red before listener exists.
  Evidence: ``

- [x] **TDD-52** — PurchaseMenu GUI (IFramework, right-click trade)
  References: REQ-013, REQ-014
  Tag: TDD
  Description: Failing test: right-click registered shop sign → PurchaseMenu opens showing item, price, stock. Implement PurchaseMenu (ChestGui) + ShopInteractListener routing.
  Evidence: ``

- [x] **TDD-53** — Real inventory trade from container
  References: REQ-013, REQ-017
  Tag: TDD
  Description: Failing test: BUY/SELL trade takes item from container inventory (not virtual), updates container on success. Implement ShopTradeService replacement using container items.
  Evidence: ``

- [x] **TDD-54** — Sign and container break protection
  References: REQ-015
  Tag: TDD
  Description: Failing test: breaking a shop sign → cancelled + owner gets edit menu. Breaking a linked container → all linked shops deleted. Implement BlockProtectionListener.
  Evidence: ``

- [x] **TDD-55** — Explosion cleanup
  References: REQ-016
  Tag: TDD
  Description: Failing test: EntityExplodeEvent destroys shop containers → shops cleaned up. Implement ExplodeCleanupListener.
  Evidence: ``

- [x] **TDD-56** — Container stock refresh on inventory change
  References: REQ-017
  Tag: TDD
  Description: Failing test: InventoryClickEvent in linked container → sign text refreshes with trade count. Implement ContainerStockListener.
  Evidence: ``

- [x] **TDD-57** — Trust management GUI
  References: REQ-018
  Tag: TDD
  Description: Failing test: owner opens trust menu → can add/remove trusted UUIDs. Implement TrustManageMenu.
  Evidence: ``

- [x] **TDD-58** — Hopper control
  References: REQ-019
  Tag: TDD
  Description: Failing test: hopper attempting to insert/extract from container with hopperAllowIn/Out=false is blocked. Implement HopperControlListener.
  Evidence: ``

- [x] **TDD-59** — Shop freezing
  References: REQ-023
  Tag: TDD
  Description: Failing test: frozen shop rejects all trades, shows frozen message. Implement freeze toggle in ShopEditMenu.
  Evidence: ``

- [x] **TDD-60** — Bedrock Cumulus menus for create/purchase/edit
  References: REQ-011, REQ-012, REQ-013
  Tag: TDD
  Description: Failing test: Bedrock player creates/purchases/edits shop → Cumulus form used. Implement BedrockCreateShopMenu, BedrockPurchaseMenu, BedrockShopEditMenu extending BaseBedrockMenu.
  Evidence: ``

---

## Milestone M6 — Guild integration & event emission

References: REQ-024 through REQ-027

### TDD tasks

- [x] **TDD-70** — Add guild_id + creator_id to shop_items (V005 migration)
  References: REQ-024, docs/db-schema.md
  Tag: TDD
  Description: Failing test: V005 migration adds guild_id (VARCHAR(36), nullable) and creator_id (VARCHAR(36), nullable) columns to shop_items. ShopRepositorySql.findById returns Shop with guildId/creatorId populated. Confirm red.
  Evidence: `V005__shop_guild.sql created, Shop.guildId/creatorId added, all 235 tests pass`

- [x] **TDD-71** — ShopRepositorySql guild queries
  References: REQ-024
  Tag: TDD
  Description: Failing test: `findByGuildId(guildId)` returns all shops owned by a guild; `setGuildOwnership(shopId, guildId, creatorId)` updates guild_id and creator_id; `removeGuildOwnership(shopId)` clears them. Confirm red.
  Evidence: `ShopRepository + ShopRepositorySql extended, 3 tests added, all pass`

- [x] **TDD-72** — Fire ShopCreatedEvent and ShopDeletedEvent
  References: REQ-026
  Tag: TDD
  Description: Failing test: ShopCreateListener creates shop → Bukkit event `net.badgersmc.em.events.ShopCreatedEvent` fired with owner UUID. Shop deletion (break, inventory break) → `ShopDeletedEvent` fired. Confirm red. Event classes in `net.badgersmc.em.events` package with matching fields.
  Evidence: ``

- [x] **TDD-73** — Fire PostShopTransactionEvent
  References: REQ-027
  Tag: TDD
  Description: Failing test: ContainerTradeService executes BUY trade → Bukkit event `net.badgersmc.em.events.PostShopTransactionEvent` fired with buyer Player, landlordId UUID, item ItemStack, quantity int, pricePaid double. Confirm red.
  Evidence: `ContainerTradeService fires PostShopTransactionEvent after BUY/SELL, 235 tests pass`

- [x] **TDD-74** — Fire ShopStockDepletedEvent
  References: REQ-026
  Tag: TDD
  Description: Failing test: ContainerStockListener detects stock reached 0 in linked inventory → Bukkit event `net.badgersmc.em.events.ShopStockDepletedEvent` fired with owner UUID. Confirm red.
  Evidence: `ContainerStockListener fires ShopStockDepletedEvent when stock=0, tests pass`

- [x] **TDD-75** — Guild income routing in ContainerTradeService
  References: REQ-025
  Tag: TDD
  Description: Failing test: ContainerTradeService completes BUY on guild-owned shop → Vault withdraw from buyer, deposit to GuildVaultService (not player Vault). Player-owned shop still routes to player Vault. Confirm red. GuildVaultService is a provided interface via LumaGuildsHook.
  Evidence: `ContainerTradeService routes guild shop income to GuildProvider, 5 new tests`

- [x] **TDD-76** — Shop guild registration service
  References: REQ-024
  Tag: TDD
  Description: Failing test: `ShopGuildService.registerGuildShop(shopId, guildId, playerId)` sets guild ownership and returns updated Shop. Duplicate registration returns failure. Confirm red.
  Evidence: `ShopGuildService created with 4 operations, 11 tests, all pass`

- [x] **TDD-77** — Mass auction launch service
  References: REQ-028
  Tag: TDD
  Description: `AuctionLifecycleService.startMassAuction(startingBid, durationStr)` iterates `stallRepository.byState(UNOWNED)`, creates one OPEN auction per stall sharing `startAt`/`endAt`, transitions stall to `AUCTIONING`, skips stalls with existing open auction, returns `MassAuctionReport(created, skipped, errors, auctionIds)`. Validates startingBid + duration; returns `AuctionResult.Failure` on bad input. `settleExpired` reverts AUCTIONING+NONE-owner stalls to UNOWNED when auction closes with no bid.
  Evidence: `src/main/kotlin/net/badgersmc/em/application/AuctionLifecycleService.kt:startMassAuction`

- [x] **TDD-78** — Live auction browser GUI
  References: REQ-029
  Tag: TDD
  Description: `AuctionBrowserMenu` (IFramework ChestGui, 6 rows). Top 5 rows = `PaginatedPane` of every `auctions.allOpen()` sorted by current `SortMode`. Bottom row = prev / sort cycle / page indicator / next / close. `BukkitTask` re-renders contents every 20 ticks while `player.openInventory.topInventory == gui.inventory`; cancels on close. Sort modes: HIGHEST_BID, LOWEST_BID, ENDING_SOON, ENDING_LATEST. `/em auctions` subcommand opens menu (permission `enthusiamarket.auction.list`).
  Evidence: `src/main/kotlin/net/badgersmc/em/interaction/gui/AuctionBrowserMenu.kt`, `src/main/kotlin/net/badgersmc/em/infrastructure/commands/AdminCommands.kt:auctionsBrowse`

## Phase 5 — ARM-inspired features (in progress)

### Completed tasks

- [x] **TDD-200** — Stall member roster domain model
  References: REQ-200, REQ-201
  Tag: TDD
  Description: Add `members: Set<UUID>` and `maxMembers: Int` to the `Stall` aggregate. Operations: `addMember(uuid): Stall`, `removeMember(uuid): Stall`, both pure and total. Reject `addMember` when `maxMembers >= 0 && members.size >= maxMembers`. Persist via new columns on the stall table (migration `V008__stall_members.sql`). Failing test asserts maxMembers cap rejection.
  Evidence: docs/requirements.md REQ-200 (per-stall member set distinct from owner, persisted), REQ-201 (configurable maxMembers, -1 = unlimited, reject overflow); src/main/kotlin/net/badgersmc/em/domain/stall/Stall.kt (existing data class — extend with default-valued fields to keep call sites compiling); src/main/kotlin/net/badgersmc/em/infrastructure/persistence/StallRepositorySql.kt (JDBC persistence pattern to extend); src/main/resources/migrations/V007__shop_constraints.sql (last migration — V008 is next); existing test src/test/kotlin/net/badgersmc/em/domain/stall/StallTest.kt (asserts/require pattern); java.util.UUID; kotlin.test.assertEquals, assertFailsWith, assertTrue, assertFalse.

- [x] **TDD-201** — Stall member commands
  References: REQ-202, REQ-203
  Tag: TDD
  Description: `/em stall members add|remove|list <stall> [player]` in AdminCommands (player-callable on own stalls). On mutation, sync to WorldGuard region's member set via a new `RegionMemberSync` infrastructure port. Failing test: command rejected for non-owner; accepted for owner; WG sync invoked.
  Evidence: docs/requirements.md REQ-202 (member commands sync to WG + persist), REQ-203 (member grants WG build/interact via region membership); src/main/kotlin/net/badgersmc/em/infrastructure/worldguard/WorldGuardRegionProvider.kt (WG adapter pattern: WorldGuard.getInstance().platform.regionContainer + BukkitAdapter.adapt(world)); src/main/kotlin/net/badgersmc/em/domain/stall/Stall.kt addMember/removeMember from TDD-200; com.sk89q.worldguard.protection.regions.ProtectedRegion getMembers; com.sk89q.worldguard.domains.DefaultDomain addPlayer/removePlayer; src/main/kotlin/net/badgersmc/em/infrastructure/commands/AdminCommands.kt (existing @Subcommand pattern); src/test/kotlin/net/badgersmc/em/infrastructure/commands/AdminCommandsTest.kt (MockK ctor-injection pattern); io.mockk.{mockk,every,verify,confirmVerified}.

- [x] **Audit M11** — Guild-stall rent collection (guild bank charge + grace/eviction; extension draws the bank)
  References: audit finding M11
  Tag: TDD
  Description: Collect rent on GUILD-owned stalls from the guild bank. RentCollectionService now branches charge by owner type (SOLO→economy, GUILD→bank). StallRentExtensionService.extend branches charge + refund. 3 new RentCollectionServiceTest + new StallRentExtensionServiceTest with guild cases. SOLO behavior unchanged.
  Evidence: docs/superpowers/specs/2026-06-04-guild-rent-collection-design.md; RentCollectionService.kt processStall; StallRentExtensionService.kt extend; RentCollectionServiceTest.kt; StallRentExtensionServiceTest.kt.

### Pending tasks

- [x] **TDD-210** — Limit group config + resolution
  References: REQ-210, REQ-211, REQ-213
  Tag: TDD
  Description: Add `limits.<name>` block to `EnthusiaMarketConfig` (named groups with `total: Int` + `regionkinds: Map<String, Int>`). New `LimitResolutionService` resolves a player's effective limits by combining every `enthusiamarket.limit.<name>` permission they hold (best value wins per key). Admin bypass via `enthusiamarket.admin.bypasslimit`. Failing tests cover: single group, multi-group merge, admin bypass.
  Evidence: docs/requirements.md REQ-210 (limits.<name>.{total, regionkinds}; -1 = unlimited), REQ-211 (best value per dimension across permission-held groups), REQ-213 (enthusiamarket.admin.bypasslimit grants unlimited); src/main/kotlin/net/badgersmc/em/config/EnthusiaMarketConfig.kt (existing nested-class config pattern); kaml-jvm supports Map<String, Pojo> via Nexus ConfigManager; existing infrastructure permission pattern src/main/kotlin/net/badgersmc/em/infrastructure/listeners/ShopCreateListener.kt:113 (player.hasPermission); new PermissionChecker port needed to keep LimitResolutionService out of the Bukkit dependency cone; io.mockk.{mockk,every,verify}; org.junit.jupiter.api.Test, kotlin.test.assertEquals.

- [x] **TDD-211** — Limit enforcement on stall claim
  References: REQ-212
  Tag: TDD
  Description: Gate stall acquisition paths (auction settlement, sell-offer purchase, `/em setowner`) on `LimitResolutionService.canClaim(player, stall)`. Reject with translated lang message `limits.exceeded`. Failing test: player at total cap rejected; player at kind cap rejected; admin always accepted.
  Evidence: docs/requirements.md REQ-212; src/main/kotlin/net/badgersmc/em/application/AuctionLifecycleService.kt settleWithWinner at lines 307-329 (settlement entry — limit gate goes BEFORE economy.withdraw to avoid charging on rejection); LimitResolutionService.canClaim/ClaimDecision from TDD-210; existing no-bid branch (lines 282-296) is the right shape to fall through to on rejection (revert AUCTIONING→UNOWNED, close auction without paying). Sell-offer and /em setowner gates deferred: SellOfferService lands in TDD-261; no `setowner` subcommand exists in AdminCommands today. Region kind isn't a Stall field yet (lands with TDD-220) so settlement uses `kind = "default"` for now. io.mockk.{mockk,every,verify,confirmVerified}; org.junit.jupiter.api.Test; kotlin.test.{assertEquals,assertIs}.

- [ ] **TDD-220** — Entity limit group domain
  References: REQ-220, REQ-222
  Tag: TDD
  Description: New `EntityLimitGroup` value object: per-`EntityType` cap + total cap + extras. Attach to `RegionKind`. New `entitylimits.yml` resource + config-load path. Per-stall override stored on `Stall` (`extraEntities`, `extraTotal`). Failing test: group lookup by region kind name; per-stall override merged correctly.
  Evidence: ``

- [ ] **TDD-221** — Entity limit enforcement listener
  References: REQ-221
  Tag: TDD
  Description: `EntityLimitListener` handles `CreatureSpawnEvent`, `EntityPlaceEvent`, `HangingPlaceEvent`. Looks up enclosing stall via WorldGuard, checks against `EntityLimitGroup` count of existing entities, cancels event when over cap. Failing test (MockBukkit): spawn at cap → cancelled; spawn under cap → allowed.
  Evidence: ``

- [x] **TDD-230** — Region info card (command only)
  References: REQ-230
  Tag: TDD
  Description: `/em stall info <stall>` subcommand renders multi-line MiniMessage info card via new lang keys `stall.info.*`.
  Evidence: ``
  Note: REQ-231 sign-click info routing deferred — PurchaseSign has no kind discriminator; revisit post-release.

- [x] **TDD-240** — Particle border outline
  References: REQ-240, REQ-241
  Tag: TDD
  Description: New `ParticleBorderService` (`@Component`). Tracks active outlines as `(player, stall, expiresAt)` triples. Bukkit repeat task at 4-tick interval traces WorldGuard region's bounding box with `Particle.END_ROD` visible only to the requesting player. Hard cap of `particles.maxPerTick` (default 200) — degrade by widening spacing rather than dropping outlines. Failing test: outline added/removed; particle count bound respected.
  Evidence: ``

- [x] **TDD-250** — Stall purchase sign domain
  References: REQ-250, REQ-253
  Tag: TDD
  Description: Domain type `PurchaseSign(stallId, world, x, y, z, kind: BUY|RENT|EXTEND|INFO)`. New `PurchaseSignRepository` port + JDBC impl. Migration `V009__purchase_signs.sql`. Domain operations: render text for current stall state. Failing test: sign render output matches expected lang template per stall state.
  Evidence: ``

- [x] **TDD-251** — Sign creation listener
  References: REQ-251
  Tag: TDD
  Description: `SignChangeEvent` handler: when line 1 == config trigger token and line 2 names an existing stall, register a `PurchaseSign` and re-render lines from lang template. Permission `enthusiamarket.sign.create` required. Failing test: valid sign → persisted + lines overwritten; invalid stall name → rejected with player message.
  Evidence: ``

- [x] **TDD-252** — Sign auto-refresh on state change
  References: REQ-252
  Tag: TDD
  Description: Emit a new `StallStateChangedEvent` from `AuctionLifecycleService.settleWithWinner`, `RentCollectionService.evict`, and `SellOfferService.complete`. Listener `PurchaseSignRefreshListener` finds all signs bound to the stall and re-renders within one tick (uses NexusScheduler.runOnMain). Failing test: state change fires event; signs in repo are re-rendered.
  Evidence: ``

- [x] **TDD-253** — Sign click action
  References: REQ-250
  Tag: TDD
  Description: `PlayerInteractEvent` handler routes right-clicks on registered purchase signs to the appropriate flow: `BUY` → trigger sell-offer purchase or auction bid menu; `RENT`/`EXTEND` → invoke RentService extension; `INFO` → display REQ-230 card. Failing test: click on BUY sign with open offer → SellOfferService.purchase invoked.
  Evidence: ``

- [x] **TDD-260** — Sell offer domain
  References: REQ-260, REQ-263
  Tag: TDD
  Description: New domain types `SellOffer(stallId, sellerUuid, price, createdAt)` + `SellOfferRepository`. Migration `V009__sell_offers.sql`. Service `SellOfferService.create(stallId, seller, price)`: rejects when stall has open auction (REQ-263); rejects when seller is not the owner; persists offer; fires `SellOfferCreatedEvent`. Failing test: each rejection path + happy path.
  Evidence: docs/requirements.md REQ-260 (owner creates public offer), REQ-263 (mutex with auction both directions); src/main/kotlin/net/badgersmc/em/domain/auction/AuctionRepository.kt (repo interface shape); src/main/kotlin/net/badgersmc/em/domain/stall/Stall.kt canManage for auth gate; src/main/kotlin/net/badgersmc/em/events/ShopCreatedEvent.kt (Bukkit Event pattern, HandlerList); io.mockk.{mockk,every,verify,confirmVerified}; using V009__sell_offers.sql since V008 is taken by feat/arm-port-member-roster PR #9.

- [x] **TDD-261** — Sell offer acceptance
  References: REQ-261, REQ-264
  Tag: TDD
  Description: `SellOfferService.purchase(stallId, buyer)`: withdraws `price * (1 + taxPct)` from buyer via EconomyProvider, deposits `price` to seller, deposits `price * taxPct` to tax destination (config: `shop.taxDestination`, default `system` = no-op sink), reassigns ownership, marks offer closed. All within a single transactional boundary; rolls back on any failure. Failing test: buyer balance insufficient → rejected; happy path → balances + ownership all updated.
  Evidence: docs/requirements.md REQ-261, REQ-264; src/main/kotlin/net/badgersmc/em/application/AuctionLifecycleService.kt settleWithWinner (existing withdraw→save→deposit ordering as compensating-actions pattern); src/main/kotlin/net/badgersmc/em/domain/ports/EconomyProvider.kt; tax routing: parse a UUID from config.shop.taxDestination or treat as system sink (no deposit) when value is "system" or invalid UUID — same compromise EM's existing tax flow uses.

- [x] **TDD-262** — Sell offer commands + cancellation
  References: REQ-260, REQ-262
  Tag: TDD
  Description: Player commands `/em stall offer <price>`, `/em stall offer cancel`, `/em stall buy <stall>`. Listed in `/em stall info` output (REQ-230). Failing test: cancel by non-owner rejected; cancel by owner closes offer.
  Evidence: docs/requirements.md REQ-260, REQ-262; src/main/kotlin/net/badgersmc/em/infrastructure/commands/AdminCommands.kt @Subcommand("stall ...") pattern; reverse mutex enforcement on AuctionLifecycleService.createAuction needs new SellOfferRepository dep (constructor sites in test require update; relaxed-mockk default keeps existing tests untouched).

- [x] **TDD-270** — Schematic capture on first claim
  References: REQ-270, REQ-273, REQ-274
  Tag: TDD
  Description: New `SchematicService` (`@Component`) with WE/FAWE adapter selection at construction time. `capture(stallId, region): Result` saves to `plugins/EnthusiaMarket/schematics/<stallId>.schem`. Hooked from `AuctionLifecycleService.settleWithWinner` BEFORE persisting ownership change. On failure: abort transition + refund + fire `SchematicCaptureFailedEvent`. Honour `schematics.enabled: false` (no-op). Failing test (with FAWE mock): capture invoked once per stall lifetime; capture failure aborts and refunds.
  Evidence: Domain port src/main/kotlin/net/badgersmc/em/domain/ports/SchematicService.kt (Result Success/Disabled/Failure; capture/restore; Disabled no-op object — no WE/Bukkit types crossing the boundary). Application hook src/main/kotlin/net/badgersmc/em/application/AuctionLifecycleService.kt settleWithWinner lines 384-409: capture runs AFTER economy.withdraw, BEFORE awardTo/save; on Result.Failure → refund (economy.deposit), revert AUCTIONING→UNOWNED, close auction, fireCaptureFailed, return; gated on config.schematics.enabled (REQ-273). fireCaptureFailed helper (~line 483) mirrors fireStateChanged. Event src/main/kotlin/net/badgersmc/em/events/SchematicCaptureFailedEvent.kt. Infra adapter src/main/kotlin/net/badgersmc/em/infrastructure/worldguard/WorldEditSchematicAdapter.kt (@Component, injects Plugin+EnthusiaMarketConfig; WG RegionContainer → cuboid bounds → ForwardExtentCopy into BlockArrayClipboard → nexus WorldEditAdapter.saveSchematic; idempotent skip when file exists per REQ-270; restore pastes via ClipboardHolder, async via BukkitRunnable when WorldEditAdapter.isFawePresent else inline — REQ-272). Config block EnthusiaMarketConfig.Schematics (enabled, directory). Tests src/test/kotlin/net/badgersmc/em/application/AuctionLifecycleSchematicTest.kt (3 tests: capture-before-award via verifyOrder + exactly-once; capture-failure refunds winner 150L & never saves winner ownership; disabled skips capture). Full suite green.

- [x] **TDD-271** — Schematic restore on unclaim
  References: REQ-271, REQ-272
  Tag: TDD
  Description: `SchematicService.restore(stallId, region): Result` pastes the stored schematic. Hooked from rent-eviction, sell-offer-completion (only when going UNOWNED), and `/em stall reset <id>`. FAWE path runs async on the WE worker pool; non-FAWE path runs sync on main thread. Failing test: restore called → region contents replaced.
  Evidence: `src/main/kotlin/net/badgersmc/em/application/RentCollectionService.kt (5th ctor param schematics defaulted to SchematicService.Disabled; restore called after clearOwnersAndMembers in the GRACE→UNOWNED evict branch, gated on config.schematics.enabled, failure logged not rethrown per REQ-273); voluntary sellback — src/main/kotlin/net/badgersmc/em/application/StallSellbackService.kt (7th ctor param schematics; restore replaces the old TDD-271 TODO after fireStateChanged, best-effort). Tests src/test/kotlin/net/badgersmc/em/application/SchematicRestoreTest.kt (4): eviction restores stall_02; eviction skips restore when disabled; sellback restores stall_01; sellback skips restore when disabled. Full suite green. Deferred (no current code path): `/em stall reset` admin subcommand and lease-expiry transition do not exist yet — wire restore there when those tasks land.

- [x] **TDD-280** — RegionProvisioner port + WG adapter
  References: REQ-270 (replaces ARM flag set), REQ-271, REQ-272
  Tag: TDD
  Description: New domain port `RegionProvisioner.provision(world, regionId, priority): Boolean`. WG infra adapter `WorldGuardRegionProvisioner` stamps priority + core build flags (BUILD, CHEST_ACCESS, BLOCK_PLACE, BLOCK_BREAK, RIDE scoped to MEMBERS), USE to ALL, plus decoration flags (ITEM_FRAME_ROTATE, INTERACT scoped to MEMBERS). All flag constants verified against real WG 7.0.9 jar; see plan §CONFIRMED API SYMBOLS.
  Evidence: src/main/kotlin/net/badgersmc/em/domain/ports/RegionProvisioner.kt (port interface, idempotent); src/main/kotlin/net/badgersmc/em/infrastructure/worldguard/WorldGuardRegionProvisioner.kt (@Component, WG adapter, 8 flag writes per region); compileKotlin green; all 6 WG flag constants resolve.

- [x] **TDD-281** — Import wiring of region provisioning
  References: REQ-270, REQ-273
  Tag: TDD
  Description: `ImportStallsService` gains `provisioner: RegionProvisioner` + `stallPriority: Int` constructor params. Calls `provisioner.provision` on every matched region (idempotent) before create/skip decision. Result gains `provisioned: Int` field. Lang message `admin.import.result` reports provisioned count. `stallPriority` registered as DI bean in `EnthusiaMarket.onEnable`.
  Evidence: src/main/kotlin/net/badgersmc/em/application/ImportStallsService.kt (4 new params + provision loop); src/test/kotlin/net/badgersmc/em/application/ImportStallsServiceTest.kt (4 tests, all pass); src/main/kotlin/net/badgersmc/em/EnthusiaMarket.kt (stallPriority bean registration); src/main/resources/lang/en_US.yml (provisioned in result msg); src/main/kotlin/net/badgersmc/em/infrastructure/commands/AdminCommands.kt (provisioned pair passed to msg); full verify green (detekt 0, 297 tests, shadowJar).

### INFRA tasks

- [x] **INFRA-20** — Schematics + entitylimits resource files
  References: REQ-220, REQ-270
  Tag: INFRA
  Description: Ship `src/main/resources/entitylimits.yml` with default groups (`default`, `shop`, `farm`) and ensure `plugins/EnthusiaMarket/schematics/` is created on plugin enable. Update `EnthusiaMarketConfig` with a `schematics` block (`enabled`, `directory`) and a `particles` block (`enabled`, `maxPerTick`).
  Evidence: src/main/resources/entitylimits.yml ships `default`/`shop`/`farm` groups with per-entity caps + `_total` (REQ-220). EnthusiaMarket.kt onEnable (after cfg read): creates `File(dataFolder, cfg.schematics.directory)` so the first capture never fails on a missing folder, and `saveResource("entitylimits.yml", false)` on first run (never overwrites local edits). EnthusiaMarketConfig.kt: `schematics` block (enabled, directory) shipped with TDD-270; added `particles` block (enabled, maxPerTick). compileKotlin green.

- [x] **INFRA-21** — WorldEdit + FAWE compile deps
  References: REQ-272
  Tag: INFRA
  Description: Add `compileOnly("com.sk89q.worldedit:worldedit-bukkit:7.3.0")` and `compileOnly("com.fastasyncworldedit:FastAsyncWorldEdit-Bukkit:2.11.0")` to `build.gradle.kts`. Add corresponding `paper-plugin.yml` softdepends. Document in `docs/tech-stack.md`.
  Evidence: build.gradle.kts lines 42-45 (compileOnly + testImplementation for worldedit-bukkit 7.3.0 and FastAsyncWorldEdit-Bukkit 2.11.0) + line 21 FAWE maven repo + line 74 nexus-worldedit v2.2.1. paper-plugin.yml dependencies.server: WorldEdit + FastAsyncWorldEdit softdepends (load BEFORE, required false, join-classpath true). docs/tech-stack.md §3: added worldedit-bukkit, FastAsyncWorldEdit-Bukkit, nexus-worldedit rows + corrected nexus coords to v2.2.1; §4: added WE 7.3.0 clipboard/schematic API schema row.

### INFRA tasks (existing)

- [x] **INFRA-11** — EM event classes for external integration
  References: REQ-026, REQ-027
  Tag: INFRA
  Description: Create Bukkit event classes under `net.badgersmc.em.events`: ShopCreatedEvent(UUID ownerId), ShopDeletedEvent(UUID ownerId), ShopStockDepletedEvent(UUID ownerId), PostShopTransactionEvent(Player buyer, UUID landlordId, ItemStack item, int quantity, double pricePaid). All extend Event with static HandlerList.
  Evidence: ``

---

## Post-release backlog (deferred — NOT in the next-week release)

- [ ] **TDD-290 — Emergency auction on grace expiry**
  References: REQ-280 (DRAFT), REQ-271, REQ-004, REQ-009
  Tag: TDD
  Description: Replace the direct GRACE→UNOWNED eviction in `RentCollectionService.processStall`
  with a transition to `EMERGENCY_AUCTIONING` that opens a system auction for the stall's
  ownership (reuse `AuctionLifecycleService`). On settlement: high bidder → awarded; no bids →
  UNOWNED. Fire the schematic restore (REQ-271) when the emergency auction is **created** so the
  stall is auctioned clean (confirmed intent 2026-06-01). Wire the currently-dead
  `EMERGENCY_AUCTIONING` (and clarify `RE_AUCTIONING`) `StallState` values — they exist in the
  enum and are handled defensively in switches, but nothing transitions into them today.
  Brainstorm/spec REQ-280 out of DRAFT first (open questions: no-bid reset, RE_ vs EMERGENCY_
  split). Current release behaviour: grace expiry evicts directly + resets immediately.
  Evidence: ``

---

## Task authoring rules

1. Every task has exactly ONE tag (`TDD`, `DOC`, or `INFRA`).
2. `References:` cites at least one REQ-ID from `requirements.md`. If the REQ doesn't exist, run `/spear:spec` first.
3. `Evidence:` starts empty (```). It must be filled before any skill past `spec-done` will run (REQ-030). Each line is a verified source (e.g. `context7:react@19/useEffect`, `src/domain/Order.kt:42`, `docs/implementation.md#3.1`).
4. Task size ceiling: ~1500 tokens of full briefing. If larger, split.
5. A task MUST be achievable by a single SPEAR cycle (`spec → prove → engine → arch → refine` for TDD; `spec → arch → refine` for DOC/INFRA).
6. Mark state as work proceeds: `[~]` when entering `spec`; `[x]` only when `/spear:refine` has cleared state to `idle`.

- [x] ItemShops parity SP1 — /shop management commands (list/edit/trust/untrust/delete/breakdelete)
- [x] ItemShops parity SP2 — /shop search (searchEnabled opt-in, results GUI with live stock)
- [x] ItemShops parity SP5 — admin tooling (/shop admin view/info/remove/fix/breakothers + search teleport)
- [x] ItemShops parity SP6 — misc/integration (transaction log + /shop history, owner notifications, PAPI placeholders, sign-click info card)
- [x] ItemShops parity SP3 — barter / profits vault (TRADE sign direction, ShopVault, per-owner item vault, /shopvault)
- [x] ItemShops parity SP4 — limits + market regions (no-group=unlimited fix, buyout gate, real stall.kind, /em limit, bypass node)

## Audit 2026-06-09 — runtime wiring fixes (see docs/audits/EM-AUDIT-2026-06-09-runtime-wiring.md)

- [x] W-1 `INFRA` — annotate all Bukkit listeners with nexus `@Listener` (9 were never registered under lazy DI); guarded by `architecture/ListenerWiringTest`
- [x] W-2 `INFRA` — eagerly `getBean` RentScheduler + AuctionScheduler in onEnable (rent collection and auction settlement never ran)
- [x] W-3 `INFRA` — delete dead legacy sign-trade path (REQ-005/006 superseded): SignInteractListener, ShopTradeService, ItemProvider, ShopSign, SignRepository(+Sql)
- [x] M-2 `TDD` — insolvent auction winner: close + revert instead of throwing (was: infinite re-settle every scheduler tick)
- [x] M-3 `TDD` — portable UPDATE-then-INSERT upserts in ShopVaultRepositorySql + GuildTradePolicyRepositorySql (SQLite-only syntax broke the mariadb config); guarded by `architecture/SqlPortabilityTest`
- [x] M-4 `TDD` — eviction (rent default + /em evict) wipes bound shops, parity with sellback
- [x] N-1 `TDD` — lenient guild_id/creator_id parsing in ShopRepositorySql.mapRow

---

## Perf 2026-06-14 — hopper control hot-path DB I/O (spark: 5.85% server thread)

Root cause: `HopperControlListener.onHopperMove` (fires per hopper transfer tick) calls
`ShopRepository.findByContainer` → synchronous SQLite query on the server thread, up to twice
per event, with no in-memory short-circuit. `idx_shop_container` already exists, so the cost is
call frequency, not query plan (`prepareStatement` alone = 2.16%). Fix: an authoritative
in-memory shop-container index; the hot path never touches the DB. Spark report
https://spark.lucko.me/3RFbDGJIef.

**Sync mechanism — DECIDED 2026-06-14: decorator, not events.** All ~25 `ShopRepository`
consumers (application services, listeners, commands, Bedrock forms, GUI menus) inject the
`ShopRepository` *interface*; DI resolves it via `getBean<ShopRepository>()`. An
`IndexedShopRepository` decorator therefore covers every mutation path transparently with zero
call-site edits, and is the single choke point that cannot be bypassed. Event-driven sync was
rejected: `ShopCreatedEvent` fires only in `SignPlaceListener:216` (NOT in `CreateShopMenu:82`
or `BedrockCreateShopForm:59`), and `ShopDeletedEvent` misses `DeleteShopsMenu:38` /
`ShopEditMenu:131` — those gaps would leave locked shops hopper-exploitable. Container coords are
set only at insert (no relocate path), so create + delete fully cover location changes.

### TDD tasks

- [x] **PERF-1** — `ShopLocationIndex` domain port + in-memory adapter
  References: REQ-282, implementation.md §2 (layer rules), §3.4 (ports), src/main/kotlin/net/badgersmc/em/domain/shop/ShopRepository.kt
  Tag: TDD
  Description: Add domain port `domain/shop/ShopLocationIndex` exposing `shopsAt(world, x, y, z): List<Shop>`
  (empty when none), `put(shop)`, `remove(shop)`, `rebuild(shops: Collection<Shop>)`. Add an in-memory
  adapter (application layer, stdlib only) keyed by a packed (world, x, y, z) coordinate. Failing test
  first: `shopsAt` empty before population; returns indexed shops after `put`/`rebuild`; reflects
  `remove`. Port carries no Bukkit/framework imports (arch denylist).
  Evidence:
  ```
  src/main/kotlin/net/badgersmc/em/domain/shop/ShopRepository.kt (port lives in domain/shop; new ShopLocationIndex port sits beside it)
  src/main/kotlin/net/badgersmc/em/domain/shop/Shop.kt:31-50 (Shop data class — container key fields containerWorld/containerX/containerY/containerZ; hopperAllowIn/hopperAllowOut default true)
  docs/implementation.md §2 (layer rules: domain depends on nothing outside domain + Kotlin stdlib; application depends on domain only) + denylist (org.bukkit/io.papermc/koin/hikari forbidden in domain)
  docs/implementation.md §3.4 (port interfaces live in domain)
  src/test/kotlin/net/badgersmc/em/domain/stall/StallTest.kt:1-24 (test conventions: kotlin.test @Test, assertEquals/assertTrue/assertFalse)
  src/test/kotlin/net/badgersmc/em/application/ContainerTradeServiceTest.kt:51-64 (testShop(...) builder pattern to reuse for fixtures)
  test imports: net.badgersmc.em.domain.shop.Shop ; java.util.UUID ; kotlin.test.Test ; kotlin.test.assertEquals ; kotlin.test.assertTrue
  impl imports: net.badgersmc.em.domain.shop.Shop ; net.badgersmc.em.domain.shop.ShopLocationIndex (port created this task)
  ```

- [x] **PERF-2** — index stays consistent with persisted shop mutations
  References: REQ-282, src/main/kotlin/net/badgersmc/em/infrastructure/persistence/ShopRepositorySql.kt, implementation.md §2
  Tag: TDD
  Description: Implement `IndexedShopRepository` (application layer) decorating any `ShopRepository`
  delegate (decision locked above): delegate every mutation (upsert/insert/update, delete,
  deleteByContainer, deleteByOwner, setGuildOwnership, removeGuildOwnership), update the
  `ShopLocationIndex` after each, and serve `findByContainer` from the index. Upsert replaces the
  prior entry for the same id so changed hopperAllowIn/Out flags are visible (no duplicate);
  `delete(id)` resolves coords via delegate `findById` (write path, not the hot path). `updateStock`
  need not move index keys. (Double-chest half-resolution is PERF-3's listener concern, not the
  repo's.) Failing test first with a mockk delegate: after each mutating op the index matches the
  persisted set; `findByContainer` returns from the index with `verify(exactly = 0)` on the
  delegate's `findByContainer`.
  Evidence:
  ```
  src/main/kotlin/net/badgersmc/em/domain/shop/ShopRepository.kt (interface the decorator implements + delegates to; upsert returns persisted Shop, delete(id), deleteByContainer, deleteByOwner, set/removeGuildOwnership, updateStock)
  src/main/kotlin/net/badgersmc/em/domain/shop/ShopLocationIndex.kt (PERF-1 port: shopsAt/put/remove/rebuild)
  src/main/kotlin/net/badgersmc/em/application/InMemoryShopLocationIndex.kt (PERF-1 adapter used as the real index collaborator in the test)
  src/main/kotlin/net/badgersmc/em/application/ShopManagementService.kt:36-103 (mutation surface: upsert on edit, delete(id), deleteByOwner)
  src/main/kotlin/net/badgersmc/em/infrastructure/persistence/ShopRepositorySql.kt:16-37,92-110 (delegate semantics: upsert→insert returns Shop with generated id; findByContainer query being replaced on hot path)
  src/test/kotlin/net/badgersmc/em/application/ShopManagementServiceTest.kt:1-55 (mockk pattern: mockk(relaxed=true), every{}returns/answers, verify(exactly=0))
  build.gradle.kts:119 (io.mockk:mockk:1.13.11 testImplementation)
  test imports: io.mockk.mockk ; io.mockk.every ; io.mockk.verify ; net.badgersmc.em.domain.shop.Shop ; net.badgersmc.em.domain.shop.ShopRepository ; java.util.UUID ; kotlin.test.Test ; kotlin.test.assertEquals ; kotlin.test.assertTrue ; kotlin.test.assertFalse
  impl imports: net.badgersmc.em.domain.shop.Shop ; net.badgersmc.em.domain.shop.ShopRepository ; net.badgersmc.em.domain.shop.ShopLocationIndex ; java.util.UUID
  ```

- [x] **PERF-3** — hopper control resolves via index, zero DB queries on server thread (MERGED INTO PERF-4 2026-06-15: listener needs no code change — PERF-2's decorator already makes findByContainer index-backed, so no TDD red is possible; the hopper guard test ships as PERF-4's regression proof)
  References: REQ-281, src/main/kotlin/net/badgersmc/em/infrastructure/listeners/HopperControlListener.kt
  Tag: TDD
  Description: With the decorator serving `findByContainer` from memory, `HopperControlListener` likely
  needs no code change — it keeps its `ShopRepository` dependency and receives the decorator via DI.
  Failing test first: inject an `IndexedShopRepository` built over a SQL-delegate spy whose
  `findByContainer`/`queryMany` fail the test if invoked, populate the index, drive the hopper decision,
  and assert correct cancel decisions for hopperAllowIn/hopperAllowOut and pass-through for non-shop
  containers — proving REQ-281's "no DB query on the server thread". If the listener is left untouched,
  this task reduces to the guarding test only.
  Evidence: ``

### INFRA tasks

- [x] **PERF-4** — DI wiring: build, rebuild-on-enable, inject (+ PERF-3 guard test) [code done; runtime DI-boot verification pending — see PERF-VERIFY]
  References: REQ-281, REQ-282, src/main/kotlin/net/badgersmc/em/EnthusiaMarket.kt, implementation.md §3.9
  Tag: INFRA
  Description: Drop `@Repository` from `ShopRepositorySql` (a second bean under the `ShopRepository`
  interface makes nexus DI ambiguous), build `ShopRepositorySql → InMemoryShopLocationIndex →
  IndexedShopRepository` in `onEnable` after the DataSource is registered and before
  registerPaperCommands/registerNexusListeners construct consumers, rebuild the index from
  `shopSqlRepo.all()` (REQ-282), and register the decorator as the sole `ShopRepository` bean (+ the
  index under `ShopLocationIndex`). All ~25 consumers inject the interface, so they transparently get
  the decorator; `HopperControlListener` then resolves from the index (REQ-281). Ships the PERF-3
  guard test. Live verification required (no test boots the DI graph).
  Evidence:
  ```
  src/main/kotlin/net/badgersmc/em/EnthusiaMarket.kt:103-122 (wiring inserted right after ctx.registerBean dataSource, before registerPaperCommands at ~125; fully-qualified refs so no new imports)
  src/main/kotlin/net/badgersmc/em/infrastructure/persistence/ShopRepositorySql.kt:12 (@Repository removed; constructed manually now)
  src/main/kotlin/net/badgersmc/em/application/IndexedShopRepository.kt (PERF-2 decorator wired as the bean)
  src/main/kotlin/net/badgersmc/em/application/InMemoryShopLocationIndex.kt (PERF-1 index, rebuilt on enable)
  src/main/kotlin/net/badgersmc/em/domain/shop/ShopLocationIndex.kt (registered under this type)
  nexus DI mechanics (nexus-core v2.1.1 sources): ComponentRegistry.typeIndex indexes a bean under its type + implemented interfaces; BeanFactory.getBean(type) throws "Multiple beans found of type" when >1 match — so ShopRepositorySql must not stay @Repository while the decorator is registered under ShopRepository. registerBean runs post-create, before first getBean (lazy factories), so the decorator is the only ShopRepository definition.
  src/test/kotlin/architecture/ListenerWiringTest.kt (Konsist static analysis only — does not boot NexusContext, so removing @Repository does not break it)
  src/main/kotlin/net/badgersmc/em/infrastructure/listeners/HopperControlListener.kt (unchanged; receives the decorator via DI)
  guard-test imports: io.mockk.mockk ; io.mockk.every ; io.mockk.verify ; net.badgersmc.em.application.IndexedShopRepository ; net.badgersmc.em.application.InMemoryShopLocationIndex ; net.badgersmc.em.domain.shop.Shop ; net.badgersmc.em.domain.shop.ShopRepository ; org.bukkit.Location ; org.bukkit.block.Block ; org.bukkit.block.Container ; org.bukkit.event.inventory.InventoryMoveItemEvent ; org.bukkit.inventory.Inventory ; org.bukkit.inventory.ItemStack ; java.util.UUID ; kotlin.test.Test ; kotlin.test.assertTrue
  ```

### DOC tasks

- [x] **PERF-5** — document the index component + data flow
  References: REQ-281, REQ-282, implementation.md §3, docs/db-schema.md
  Tag: DOC
  Description: Add a component-design entry for `ShopLocationIndex` (layer, port, adapter, evidence
  sources) and a data-flow subsection for the hopper hot path in `implementation.md`. (db-schema.md does
  not document indexes, so no edit there — idx_shop_container now backs admin/maintenance reads, not the
  hopper hot path; recorded here instead.)
  Evidence:
  ```
  docs/implementation.md §3.9 (decorator wiring note), §3.10 (ShopLocationIndex + IndexedShopRepository component), §4.6 (hopper hot-path data flow) — added this task
  src/main/kotlin/net/badgersmc/em/domain/shop/ShopLocationIndex.kt ; src/main/kotlin/net/badgersmc/em/application/IndexedShopRepository.kt (cited as evidence sources in §3.10)
  docs/db-schema.md (inspected: no index documentation section, so nothing to amend)
  ```

- [x] **PERF-VERIFY** — live runtime verification of the hopper fix
  References: REQ-281, REQ-282
  Tag: INFRA
  Description: No unit test boots NexusContext, so PERF-4's DI wiring is unproven at runtime. On a server
  (test server, or the live server during a low-population window — NOT a hot-swap on 33 live players):
  build the shadowJar, deploy, confirm clean enable (no "Multiple beans"/"No bean" for ShopRepository,
  "EnthusiaMarket enabled" logged), exercise a locked-hopper shop, then re-run a spark profiler and
  confirm HopperControlListener.onHopperMove / ShopRepositorySql.findByContainer is gone from the
  server-thread hot path (was 5.85%, baseline https://spark.lucko.me/3RFbDGJIef).
  DI-BOOT VERIFIED 2026-06-15 on test server C:\Users\Noah\Desktop\test_net\lobby\SanityCheck (Leaf
  1.21.11, full deps): "EnthusiaMarket enabled (v0.2.0)", 0 ShopRepository DI errors, HopperControlListener
  registered (index-backed repo), 14 @Listener beans, server reached Done. (Had to swap the test box's
  stale LumaGuilds-2.1.0.jar — missing net.lumalyte.lg.api.GuildLookup — for the freshly-built one;
  stale copy saved as plugins/LumaGuilds-2.1.0.jar.stale-bak.)
  SPARK PROOF DONE 2026-06-15: fix deployed to live (remote 170.205.24.14), 25 players, TPS 20.00 (was 18.34
  @33 players pre-fix). Profile https://spark.lucko.me/ZNDUJN57Yg (3.2MB, populated): net/badgersmc =
  net.badgersmc = badgersmc/em = onHopperMove = HopperControlListener = findByContainer = ShopRepositorySql =
  IndexedShopRepository = 0 hits, while vanilla HopperBlockEntity=51 + InventoryMoveItem=10 confirm hoppers
  were active and lumalyte=57 confirms plugin frames CAN appear. EM hopper SQL hotspot (was 5.85% top of
  Server thread, baseline https://spark.lucko.me/3RFbDGJIef) is eliminated. Gotcha: `--thread "Server thread"`
  yielded an empty "No Data" profile on this Leaf server; run `/spark profiler start --timeout N` WITHOUT a
  thread filter (matches how the baseline was captured). Pre-existing unrelated finding: BlockProtectionListener,
  ExplodeCleanupListener, ShopCreateListener, ShopInteractListener fail to register — inject
  java.util.logging.Logger with no Logger bean (not caused by PERF; flagged as separate task).
  Evidence: spark report ZNDUJN57Yg vs baseline 3RFbDGJIef; live TPS via papermcp server_info.
