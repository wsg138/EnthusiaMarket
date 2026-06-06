# EnthusiaMarket — Hardening Audit v2 (read-only)

**Date:** 2026-06-06
**Type:** Read-only release-hardening review. **No code changes.** Output = a ranked findings report.
**Why v2:** The v1 release-readiness audit (→ PRs #37/#38) plus SP1–SP6 and guild-rent (#40) have landed. This pass re-sweeps the *current* `main` for anything still latent before tagging v0.1.0.

This is a curated prompt for **12 parallel read-only review subagents**. The orchestrator dispatches all 12, collects their ranked findings, dedups, and returns one consolidated report. **Nothing is fixed in this pass** — fixes are triaged into follow-up PRs afterward.

---

## Orchestrator instructions

1. `cd /opt/data/EnthusiaMarket && git fetch origin && git checkout main && git pull`. Review HEAD of `main`. **Also pull `origin/feat/guild-rent` into scope** for Domain 6 (guild rent #40 is still open).
2. Dispatch the **12 domain subagents below in parallel** (use `superpowers:dispatching-parallel-agents`). Each is **read-only** — Read/Grep/Glob only, no Edit/Write, no commits, no gradle.
3. Each subagent returns findings ranked **CRITICAL / MAJOR / MINOR**, each as: `[SEVERITY] file:line — symptom → root cause → concrete fix → repro/trigger`. Empty severity buckets say "none found."
4. Collect all 12. **Dedup** cross-domain repeats (money-flow especially overlaps). Produce one consolidated table sorted CRITICAL→MINOR, tagged by domain. End with a one-paragraph release verdict.

### Severity rubric
- **CRITICAL** — money creation/loss, item duplication/loss, ownership corruption, auth bypass, crash/data loss on a normal path. Release-blocking.
- **MAJOR** — exploit needing unusual conditions, state desync (DB vs WG), missing rollback that strands value, main-thread stall/perf cliff. Fix before release if cheap.
- **MINOR** — defensive gaps, missing validation with no value impact, UX/log/message issues, test coverage holes.

### DO NOT re-flag (already fixed — verify-only, flag ONLY if the fix regressed)
- **C1** container deliverStock leftover rollback · **C2** buyout refund-on-failed-award · **C3** auction settle close-then-refund-keep-closed · **C6** removed outer guild WG sync · **C7** sign-trade tax routed last (after item delivery) · **C8** GUILD stalls rejected in sign-trade path + SOLO self-trade guard.
- **M1/M3/M4/M5/M9** — auction/offer cleanup, rent floor, rent-extension refund, misc from #37/#38.
- **M11** guild rent (under review in #40) · **M14** console-UUID corruption (player-only admin cmds).

### KNOWN-OPEN backlog (flag if you SEE it, but tag `[KNOWN]` — don't write a novel)
- **M2** offer WG sync · **M6** stale WG members not cleared on eviction · **M10** trade-rollback `addItem` leftover · **M12** shop search does chunk I/O on main thread · **M13** create-menu re-validate before commit · **REQ-280** emergency auction (`EMERGENCY_AUCTIONING`) never triggered.
New findings beyond these are what this audit is hunting.

---

## The 12 review domains

Each subagent gets: this header block (rubric + DO-NOT-reflag + KNOWN list) plus its domain card. Read the named files end-to-end, trace the money/item/ownership paths, think like an attacker.

**D1 — Auction lifecycle.** `AuctionLifecycleService`, `AuctionRepository(Sql)`, bid/anti-snipe/settle/refund, deposit handling, `EMERGENCY_AUCTIONING`. Focus: double-settle, refund correctness on save failure (C3 regression check), escrowed-item return on cancel, concurrent bids, deposit not refunded/double-refunded.

**D2 — Stall buyout (solo + guild).** `StallBuyoutService`. Focus: withdraw→persist→event order, refund-on-failed-award (C2), limit gate vs guild skip, WG owner sync (SOLO set / GUILD skip — C6), AUCTIONING/UNOWNED state guards, price≤0, double-buy race.

**D3 — Sell offers / purchase.** `SellOfferService`, `SellOfferRepository`, offer create/cancel/purchase, tax routing, stall-state mutex with buyout. Focus: money creation via tax, item escrow, offer lingering on UNOWNED.

**D4 — Container barter + item vault (SP3).** Barter trade service, item-vault deposit/withdraw, ItemStack serialize/deserialize (`serializeAsBytes`/legacy). Focus: item dup on rollback, vault over-withdraw, NBT/data-converter loss, partial `removeItem`/`addItem` leftovers.

**D5 — Sign trade (legacy).** `ShopTradeService`. Focus: tax-last ordering (C7), GUILD reject + SOLO self-trade (C8) regression, buy/sell rollback compensation paths, `CompensationFailed` money state, tax-destination sink.

**D6 — Rent: collection + extension + grace/evict (incl. guild #40).** `RentCollectionService`, `StallRentExtensionService` on `main` AND `origin/feat/guild-rent`. Focus: guild-bank charge/refund branches, M4 floor, grace→evict transition, `nextRentAt` advance, eviction cleanup (offers + WG members M6 + schematic restore), free-stall (winningBid≤0) path, corrupt-id skip-not-evict.

**D7 — Limits + market regions (SP4).** `LimitResolutionService`, `StallOwnershipCounter`, region/limit config. Focus: per-kind vs total cap bypass, guild claims not counting toward personal cap (and vice-versa), count drift after evict/sellback, off-by-one at cap.

**D8 — Guild ownership + bank routing.** `GuildProvider` impl, `OwnerRef.guild`, every `bankWithdraw/bankDeposit/bankBalance` call site, `canManage`/`hasShopPermission` gates. Focus: a non-member binding a stall to a guild, bank routing to wrong guild id, permission gate gaps, guild-id-as-UUID misuse (C6 family).

**D9 — Admin tooling.** `/shop admin` (view/info/remove/fix/breakothers), `/em evict`, `AdminBreakMode`, `LookAtShopResolver`. Focus: permission node coverage, owner-bypass delete firing events + cleanup, breakothers state leak, raycast null handling, console-sender guards (M14 regression).

**D10 — Shop management + search (SP1/SP2).** `/shop` create/remove/trust/list, `ShopSearchService`, `ShopManagementService`. Focus: main-thread chunk I/O (M12), owner checks on mutate, search→in-memory scan scale, trusted-set auth.

**D11 — Menus / GUIs.** All `interaction/gui/*` (ShopEditMenu, SearchResultsMenu, create menu, auction/offer menus). Focus: click handlers re-validating state before committing money/items (M13), stale-snapshot actions (item changed between open & click), permission checks in click lambdas, double-click race, inventory-close exploits.

**D12 — Lifecycle / cross-cut.** `EnthusiaMarket.onEnable/onDisable`, scheduler registration, Vault-absent degradation, permission registration (PR #30 runtime regs), WG region sync, DB migrations, concurrency (main thread vs async scheduler touching same repos). Focus: services wired with deps present, scheduler shutdown, migration ordering, any repo touched off-thread, event listener double-registration.

---

## Output format (orchestrator → user)

```
# EM Hardening Audit v2 — Consolidated Findings

## CRITICAL (release-blocking)
| # | Domain | File:line | Finding | Fix |

## MAJOR
| # | Domain | File:line | Finding | Fix |

## MINOR
| # | Domain | File:line | Finding | Fix |

## KNOWN-OPEN confirmed (no action — already backlogged)
- ...

## Verdict
<release-ready? / N blockers / recommended fix batches>
```
