# EnthusiaMarket — Hardening Audit v3 (read-only)

**Date:** 2026-06-07
**Type:** Read-only release-hardening review. **No code changes.** Output = a ranked findings report.
**Why v3:** Since the v2 audit, nine PRs landed (#40, #42, #44–48) fixing the v2 blockers/majors and reworking money paths. v3 confirms those fixes **held** (no regressions) and hunts **new** issues introduced by the changed surface, before tagging v0.1.0.

This is a curated prompt for **12 parallel read-only review subagents**. The orchestrator dispatches all 12, collects ranked findings, dedups, and returns one consolidated report. **Nothing is fixed in this pass.**

---

## Orchestrator instructions

1. `cd /opt/data/EnthusiaMarket && git fetch origin && git checkout main && git pull`. Review HEAD of `main`.
2. Dispatch the **12 domain subagents below in parallel** (use `superpowers:dispatching-parallel-agents`). Each is **read-only** — Read/Grep/Glob only, no Edit/Write, no commits, no gradle.
3. Each subagent returns findings ranked **CRITICAL / MAJOR / MINOR**, each as: `[SEVERITY] file:line — symptom → root cause → concrete fix → repro/trigger`. Empty buckets say "none found."
4. Collect all 12. **Dedup** cross-domain repeats. Produce one consolidated table sorted CRITICAL→MINOR, tagged by domain. End with a one-paragraph release verdict.

### Severity rubric
- **CRITICAL** — money creation/loss, item duplication/loss, ownership corruption, auth bypass, crash/data loss on a normal path. Release-blocking.
- **MAJOR** — exploit needing unusual conditions, state desync (DB vs WG), missing rollback that strands value, main-thread stall/perf cliff. Fix before release if cheap.
- **MINOR** — defensive gaps, missing validation with no value impact, UX/log/message issues, test coverage holes.

### Threading reality (avoid v2's false-positive class)
EnthusiaMarket runs **single-threaded on the Bukkit main thread**: all commands, event handlers, inventory clicks, and BOTH schedulers (`RentScheduler`, `AuctionScheduler` use `runTaskTimer`, not async) execute on the main thread. The ONLY off-thread work is read-only (auction-browser prefetch, offline-name cache, FAWE geometry). **Do NOT report "two players race / TOCTOU / concurrent lost-update" on any money/item/ownership write path — Bukkit cannot interleave two synchronous handlers.** A finding is only valid if a repository is genuinely touched off the main thread; prove the off-thread call site or don't file it.

### DO NOT re-flag (fixed + merged — verify-only; report ONLY if the fix regressed)
From v1/v2 and the post-v2 PRs:
- **C-1/C-12** tax→`system` is an intentional, documented money sink (not a bug).
- **C-2/C-8/M-1/M-2/M-14** — false-positive concurrency (single-threaded).
- **C-15** `/em bid` now uses `enthusiamarket.auction.bid` (players can bid) — PR #42.
- **C-10** rent ticker skips a future `nextRentAt` (no double-charge); `processStall` split into `chargeRent`/`collect`/`handleFailure`/`evict`/`cleanupAfterEviction` — PR #42.
- **C-9 / M-11 / M-12** guild rent + free-stall floor — PR #40.
- **M-18** sell-offer proceeds on a guild stall pay the guild bank; buyer still SOLO — PR #44.
- **M-15** sign shops in guild stalls bind `Shop.guildId` (revenue → guild bank) — PR #44.
- **M-16** guild disband frees stalls (reuses `StallEvictionService.evict`) + unbinds shops via a registered `onDissolved` handler — PR #45.
- **C-4/C-11/C-13/C-14** compensation-failure sites now call `CompensationAlertService` (SEVERE log + `TradeCompensationFailedEvent`) — PR #46.
- **M-8** `ItemStackSerializer` writes NBT (`serializeAsBytes`), reads NBT-then-legacy fallback; `ContainerTradeService.deserializeStack` delegates to it — PR #47.
- **M-21** `ShopRepository.deleteByOwner` bulk delete; `trustAll`/`untrustAll` drop redundant re-reads — PR #48.
- Earlier hotfixes C3/C5/C6/C7, M3/M4/M5/M9 (PRs #37/#38) — already verified in v2.

### KNOWN-OPEN backlog (flag if seen, tag `[KNOWN]`, don't expand)
- **M-19** search-result trades count does main-thread chunk I/O (async-prefetch fix planned).
- **M-20** `/shop search` is a full-table in-memory scan + per-row NBT (SQL+denormalized-column fix planned).
- **M2** offer WG sync · **M13** create-menu re-validate · **REQ-280** emergency auction never triggered.

### v3's PRIMARY HUNT — the changed surface since v2
Spend the most effort verifying these reworked paths didn't introduce NEW bugs:
1. **Guild money routing** (PR #40/#44): `RentCollectionService.chargeRent` (SOLO→economy, GUILD→`bankWithdraw`), `StallRentExtensionService` guild charge/refund, `SellOfferService.purchase` guild proceeds branch, `SignPlaceListener` `guildId` set. Look for: wrong account charged/paid, guild id vs UUID confusion, `bankWithdraw`/`bankDeposit` return value ignored leaving state inconsistent, a guild stall whose owner.id isn't a valid guild.
2. **Rent state machine** (PR #40/#42): the refactored `processStall` + the `nextRentAt` due-guard. Look for: a stall that never gets charged (guard skips forever), GRACE not advancing to eviction, free-stall path mis-evaluated, `collect` writing wrong `nextRentAt`.
3. **Disband cleanup** (PR #45): `GuildDissolutionService.handle` + the `onDissolved` registration. Look for: an exception aborting the sweep, a mid-auction guild stall left half-cleaned, shop unbind partial failure, the listener not actually registered at runtime.
4. **Compensation alerts** (PR #46): the alert sites didn't change control flow — confirm each still returns the correct `CompensationFailed` and the `affected`/`amount` are right; the event fire is null-safe.
5. **Serializer migration** (PR #47): confirm the legacy read fallback actually triggers (deserializeBytes throws on legacy bytes, not silently returns a bad item), and writes are pure NBT. Look for: a path that still calls the old serializer, or data loss on round-trip.

---

## The 12 review domains

Each subagent gets this header (rubric + threading reality + DO-NOT-reflag + KNOWN list + PRIMARY HUNT) plus its domain card. Read the named files end-to-end; trace money/item/ownership; think like an attacker.

**D1 — Auction lifecycle.** `AuctionLifecycleService`, `AuctionRepository(Sql)`, bid/anti-snipe/settle/refund, deposit handling, `AuctionScheduler`. Verify C-15 bid path works for players; check double-settle, refund-on-save-failure, escrow return on cancel, deposit double-refund.

**D2 — Stall buyout (solo + guild).** `StallBuyoutService`. withdraw→persist→event order, refund-on-failed-award, limit gate vs guild skip, WG owner sync (SOLO set / GUILD skip), state guards, price≤0.

**D3 — Sell offers / purchase.** `SellOfferService`. **PRIMARY HUNT #1** — guild proceeds branch (M-18): right account paid, buyer SOLO, tax routing, `bankDeposit` return handling, offer cleanup on award.

**D4 — Container barter + item vault.** `ContainerTradeService`, `ShopVaultService`, `ShopVaultRepositorySql`. **PRIMARY HUNT #5** — serializer (M-8): `deserializeStack` delegation, legacy fallback correctness, NBT round-trip; plus vault over-withdraw, item dup/loss on inventory-full rollback (the M-10 family).

**D5 — Sign trade (legacy).** `ShopTradeService`. **PRIMARY HUNT #4** — compensation alerts (C-4/11/13/14): each `CompensationFailed` site still correct, `alerter.alert` args right, no control-flow change; tax-last ordering, GUILD reject + SOLO self-trade guard intact.

**D6 — Rent: collection + extension + grace/evict.** `RentCollectionService`, `StallRentExtensionService`. **PRIMARY HUNT #1+#2** — guild-bank charge/refund branches, the refactored `processStall` helpers, the `nextRentAt` due-guard, M4 floor, grace→evict, eviction cleanup, free-stall path, corrupt-id skip.

**D7 — Limits + market regions.** `LimitResolutionService`, `StallOwnershipCounter`. per-kind vs total cap, guild claim not counting personal cap, count drift after evict/sellback, off-by-one at cap.

**D8 — Guild ownership + bank routing.** `LumaGuildsGuildProvider`, `GuildProvider`, `OwnerRef.guild`, every `bankWithdraw/Deposit/Balance` call site, `canManage`/`hasShopPermission`. **PRIMARY HUNT #1** — non-member binding a stall/shop to a guild, wrong-guild routing, guild-id-as-UUID misuse, exception swallowing.

**D9 — Disband cleanup + admin tooling.** `GuildDissolutionService`, `GuildDisbandedEventListener`, `onDissolved` registration in `EnthusiaMarket.onEnable`; `/shop admin`, `/em evict`, `AdminBreakMode`. **PRIMARY HUNT #3** — sweep abort on exception, mid-auction guild stall, shop unbind partial failure, listener actually registered; plus admin perm coverage + console guards.

**D10 — Shop management + search.** `/shop` create/remove/trust/list, `ShopSearchService`, `ShopManagementService`. Verify M-21 bulk delete fires events per shop + trust correctness; flag M-20 (search scan) as `[KNOWN]`; trusted-set auth, owner checks on mutate.

**D11 — Menus / GUIs.** All `interaction/gui/*` (ShopEditMenu, SearchResultsMenu, AuctionBrowserMenu, create/purchase/vault menus). Click handlers re-validate state before committing money/items, stale-snapshot actions, permission checks in click lambdas, double-click. Flag M-19 (search chunk I/O) `[KNOWN]`.

**D12 — Lifecycle / cross-cut.** `EnthusiaMarket.onEnable/onDisable`, scheduler registration, Vault-absent degradation, permission registration, WG sync, DB migrations, listener auto-registration (`registerNexusListeners`). Confirm the new `onDissolved` wiring + `CompensationAlertService` + `deleteByOwner` are wired and beans resolve. Any repo touched off-thread? Migration ordering?

---

## Output format (orchestrator → user)

```
# EM Hardening Audit v3 — Consolidated Findings

## CRITICAL (release-blocking)
| # | Domain | File:line | Finding | Fix |

## MAJOR
| # | Domain | File:line | Finding | Fix |

## MINOR
| # | Domain | File:line | Finding | Fix |

## Regressions in v2/post-v2 fixes (if any)
- ...

## KNOWN-OPEN confirmed (no action)
- ...

## Verdict
<release-ready? / N blockers / did the post-v2 fixes hold?>
```
