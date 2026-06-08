# EnthusiaMarket — Full Ground-Up Audit (read-only)

**Date:** 2026-06-07
**Type:** Read-only, **exhaustive whole-codebase** review. **No code changes.** Output = a ranked findings report.
**Relationship to v3:** Run this in **parallel** with `HERMES-HARDENING-AUDIT-v3.md`. v3 is diff-focused (verify recent fixes + changed surface). **This audit is the opposite: equal-weight, file-by-file coverage of the ENTIRE codebase** — every one of the 137 source files + 16 migrations is assigned to exactly one agent and must be read. The two audits cross-validate; findings that appear in both are high-confidence.

This is a curated prompt for **12 parallel read-only review subagents**, partitioned by package for provable coverage.

---

## Orchestrator instructions

1. `cd /opt/data/EnthusiaMarket && git fetch origin && git checkout main && git pull`. Review HEAD of `main`.
2. Dispatch the **12 coverage agents below in parallel** (use `superpowers:dispatching-parallel-agents`). Each is **read-only** — Read/Grep/Glob only, no Edit/Write, no commits, no gradle.
3. **Coverage mandate:** each agent FIRST runs `Glob` on its assigned package(s) to enumerate every `.kt` file, then **reads each file end-to-end** (not just grep). The agent's report must end with a `COVERAGE:` line listing every file it read and any it could not reach. An unread file is a coverage gap to report, not a silent skip.
4. Each agent returns findings ranked **CRITICAL / MAJOR / MINOR**, each as: `[SEVERITY] file:line — symptom → root cause → concrete fix → repro/trigger`. Empty buckets say "none found."
5. Collect all 12. **Dedup** cross-domain repeats. Produce one consolidated table sorted CRITICAL→MINOR, tagged by agent, plus a combined `COVERAGE` summary (every package accounted for). End with a one-paragraph release verdict.

### Severity rubric
- **CRITICAL** — money creation/loss, item duplication/loss, ownership corruption, auth bypass, crash/data loss on a normal path. Release-blocking.
- **MAJOR** — exploit needing unusual conditions, state desync (DB vs WG), missing rollback that strands value, main-thread stall/perf cliff. Fix before release if cheap.
- **MINOR** — defensive gaps, missing validation with no value impact, UX/log/message issues, test-coverage holes.

### Threading reality (do not file false positives)
EnthusiaMarket runs **single-threaded on the Bukkit main thread**: all commands, event handlers, inventory clicks, and BOTH schedulers (`RentScheduler`, `AuctionScheduler` use `runTaskTimer`, not async) run on the main thread. The only off-thread work is read-only (auction-browser prefetch, offline-name cache, FAWE geometry). **Do NOT report "two players race / TOCTOU / concurrent lost-update" on any write path** — Bukkit cannot interleave two synchronous handlers. A concurrency finding is valid only if you can point to a genuine off-main-thread call site touching a repository.

### DO NOT re-flag (fixed + merged — verify-only; report ONLY if regressed)
- **Tax sink:** tax→`system` is an intentional, documented money sink (config comment in `EnthusiaMarketConfig`). Not a bug.
- **Single-threaded "races":** any TOCTOU/lost-update/two-player-race on a main-thread path.
- **Closed audit findings** (v1/v2 + PRs #37/#38/#40/#42/#44/#45/#46/#47/#48): C-1, C-2, C-3, C-4, C-5, C-6, C-7, C-8, C-9, C-10, C-11, C-12, C-13, C-14, C-15, M-1..M-6, M-8, M-9, M-11, M-12, M-14, M-15, M-16, M-18, M-21. Treat these as verify-only.

### KNOWN-OPEN backlog (flag if seen, tag `[KNOWN]`, don't expand)
**M-19** search trades count = main-thread chunk I/O · **M-20** `/shop search` full-table in-memory scan + per-row NBT · **M2** offer WG sync · **M13** create-menu re-validate · **REQ-280** emergency auction never triggered.

**Everything else is fair game — including code untouched since launch.** This audit's job is to find what the thematic v1/v2/v3 sweeps under-covered.

---

## The 12 coverage agents (by package — read EVERY file)

> Each agent: `Glob` the package(s), read every `.kt` end-to-end, trace money/item/ownership/auth, think like an attacker, then report findings + a `COVERAGE:` file list. Test files are NOT in scope (skip `src/test`), but note missing test coverage as MINOR.

**A — Application: economy & trade services.** Read these in `src/main/kotlin/net/badgersmc/em/application/`: `ShopTradeService`, `ContainerTradeService`, `ShopVaultService`, `SellOfferService`, `StallBuyoutService`, `AuctionLifecycleService`, `CompensationAlertService`, `ItemStackSerializer`. Focus: every economy withdraw/deposit + item give/take, rollback completeness, serialization, tax.

**B — Application: rent, eviction, ownership, limits.** `RentCollectionService`, `StallRentExtensionService`, `StallSellbackService`, `StallEvictionService`, `GuildDissolutionService`, `LimitResolutionService`, `StallOwnershipCounter`. Focus: rent state machine, eviction cleanup completeness, cap math, disband sweep.

**C — Application: shops, search, regions, misc.** Everything else in `application/` (e.g. `ShopManagementService`, `ShopGuildService`, `ShopFactory`, `ShopSearchService`, `RegionMember*`, particle/border, schematic-facing services, anything not in A/B). Focus: owner/trust auth, guild binding, search correctness, region sync.

**D — Domain: shop / sign / offer.** `domain/shop/` (9), `domain/sign/` (2), `domain/offer/` (2). Focus: invariants, value-object validation, `Shop`/`ShopSign`/`SellOffer` construction, `trusted` semantics, any business rule enforced (or not) in the domain.

**E — Domain: stall / auction / ports.** `domain/stall/` (8), `domain/auction/` (5), `domain/ports/` (8). Focus: `Stall` state transitions (`awardTo`, evict, grace), `OwnerRef`/`OwnerType`, `Auction.placeBid`/anti-snipe, `RentTerms` math, port contracts (what an impl could violate).

**F — Persistence + schema.** `infrastructure/persistence/` (8) + ALL of `src/main/resources/**/*.sql` (16 migrations). Focus: SQL injection (string-built queries), upsert/read column mapping drift, nullable handling, migration ordering/idempotency, index coverage, serialization columns, transaction boundaries (or lack).

**G — Listeners.** `infrastructure/listeners/` (16). Focus: every `@EventHandler` — `ignoreCancelled`, event mutation, permission checks, NPE on world/chunk, sign/break/place/protect/stock flows, double-fire, async misuse.

**H — GUIs / interaction.** `interaction/gui/` (11), `interaction/` (3), `interaction/bedrock/` (4). Focus: click handlers re-validate state before money/item commit, stale snapshots, permission checks in lambdas, `isCancelled`, inventory-close exploits, Bedrock form parity.

**I — Commands + small infra.** `infrastructure/commands/` (3), `infrastructure/papi/`, `infrastructure/i18n/`, `infrastructure/api/`, `infrastructure/bukkit/`, `infrastructure/bedrock/`, `infrastructure/vault/` (2), `api/` (1). Focus: every `@Subcommand` permission node + sender type guard, arg validation, PAPI placeholder safety, Vault availability handling.

**J — Integration infra: WorldGuard, LumaGuilds, schedulers.** `infrastructure/worldguard/` (4), `infrastructure/lumaguilds/` (2), `infrastructure/scheduler/` (2). Focus: WG region read/write failure handling + DB-vs-WG desync, guild provider exception swallowing + id format, scheduler thread (`runTaskTimer` vs async) and what they touch each tick.

**K — Events + config + plugin wiring.** `events/` (9), `config/` (1), `EnthusiaMarket.kt`, `infrastructure/api` if not in I. Focus: `onEnable`/`onDisable` ordering, bean wiring completeness (every `@Service`/`@Component` resolvable, every listener/handler registered), config defaults + validation, `HandlerList` correctness, DataSource lifecycle, Vault-absent degradation.

**L — Cross-cut sweep: money & item conservation.** No fixed package — this agent traces **value conservation end-to-end** across whatever files it needs: for every flow that moves money or items (auction settle, buyout, sell-offer, sign trade, container trade, rent, sellback, vault, tax), confirm the books balance on success AND every failure branch (nothing created, nothing destroyed without a compensating entry or an alert). This is the integrative pass that catches what package-scoped agents miss at the seams.

---

## Output format (orchestrator → user)

```
# EM Full Audit — Consolidated Findings

## CRITICAL (release-blocking)
| # | Agent | File:line | Finding | Fix |

## MAJOR
| # | Agent | File:line | Finding | Fix |

## MINOR
| # | Agent | File:line | Finding | Fix |

## KNOWN-OPEN confirmed (no action)
- ...

## COVERAGE
- A: <files read> … L: <files read>  — gaps: <none | list>

## Verdict
<release-ready? / N blockers / coverage complete?>
```
