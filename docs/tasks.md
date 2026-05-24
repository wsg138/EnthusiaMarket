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

- [ ] **TDD-26** — Disable services when Vault missing
  References: REQ-041
  Tag: TDD
  Description: Failing test: enabling plugin with no Vault provider skips scheduler registration and logs single error. Confirm red.
  Evidence: ` `

- [ ] **TDD-27** — Implement Vault-absent degradation
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

- [ ] **TDD-40** — Guild stall authorization
  References: REQ-010
  Tag: TDD
  Description: Failing test: guild member with required rank passes `Stall.canManage(actor)`; lower rank fails. Confirm red.
  Evidence: ` `

- [ ] **TDD-41** — Replace LumaGuilds stub with real adapter
  References: REQ-010, implementation.md §3.4
  Tag: TDD
  Description: Implement `LumaGuildsGuildProvider` against actual LumaGuilds API. Green.
  Evidence: ` `

- [ ] **TDD-42** — Bedrock player gets Cumulus form
  References: REQ-011
  Tag: TDD
  Description: Failing test: stall menu dispatch for Bedrock-tagged UUID invokes Cumulus form sender, not Bukkit GUI. Confirm red.
  Evidence: ` `

- [ ] **TDD-43** — Implement Bedrock dispatcher
  References: REQ-011
  Tag: TDD
  Description: Add `infrastructure/bedrock/UiDispatcher` checking `FloodgateApi`. Green.
  Evidence: ` `

---

## Task authoring rules

1. Every task has exactly ONE tag (`TDD`, `DOC`, or `INFRA`).
2. `References:` cites at least one REQ-ID from `requirements.md`. If the REQ doesn't exist, run `/spear:spec` first.
3. `Evidence:` starts empty (`\ \``). It must be filled before any skill past `spec-done` will run (REQ-030). Each line is a verified source (e.g. `context7:react@19/useEffect`, `src/domain/Order.kt:42`, `docs/implementation.md#3.1`).
4. Task size ceiling: ~1500 tokens of full briefing. If larger, split.
5. A task MUST be achievable by a single SPEAR cycle (`spec → prove → engine → arch → refine` for TDD; `spec → arch → refine` for DOC/INFRA).
6. Mark state as work proceeds: `[~]` when entering `spec`; `[x]` only when `/spear:refine` has cleared state to `idle`.
