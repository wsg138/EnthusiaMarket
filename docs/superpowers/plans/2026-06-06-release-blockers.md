# Release-Blocker Batch — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Fix the only two true launch-blockers surfaced by the hardening audit v2 (after false-positive triage):
- **C-15** — regular players cannot bid on auctions (`/em bid` is OP-gated; the browser GUI has no bid action). The core stall-acquisition loop is unusable by non-ops.
- **C-10** — rent collection ignores `nextRentAt`; voluntary extension (and fresh buyout) push `nextRentAt` forward but the ticker re-charges on its own schedule anyway → the pre-paid period is lost (double-charge / extension does nothing).

**Out of scope:** every other audit finding. Guild-correctness (M-15/16/17/18), money-compensation hardening (C-4/11/13/14), perf (M-8/19/20/21), and the wiki text fixes (W-1/W-2, handled on PR #41) are separate PRs. **Small PR — do not pull anything else in.**

**Dependency:** Branch off `main` **after PR #40 (guild rent) is merged.** C-10 edits `RentCollectionService.processStall`; #40 also edits it (guild charge branch). Branching post-#40 avoids a conflict, and C-9/M-11/M-12 are already closed by #40.

**Architecture:** Hexagonal/SPEAR. C-10 is a pure guard in an application service (TDD). C-15 is a command-annotation correction (framework metadata; verified by build + grep, no meaningful unit test).

**Tech Stack:** Kotlin 2.0.0, Nexus DI + commands + permissions DSL, JUnit 5 + MockK, detekt 1.23.8.

**Reference:** `docs/superpowers/plans/HERMES-HARDENING-AUDIT-v2.md` (audit), this plan.

**Standing rules (every task):**
- Prefix bash with `cd <REPO> &&` (`/d/BadgersMC-Dev/EnthusiaMarket` or `/opt/data/EnthusiaMarket`). On Hermes' box also prefix gradle with `export JAVA_HOME=/opt/data/jdk-21.0.11+10 &&`.
- Every gradle command includes `-Plumaguilds.jar=<JAR> --no-daemon --console=plain`.
- LF→CRLF warnings expected. Branch `fix/release-blockers`. Do not push (coordinator opens the PR).
- TDD where noted: failing test first, run RED, then GREEN. Commit after every task.
- Gate rule: compare each `Run:` to `Expected:`; mismatch → STOP, fix, re-run; HALT after 3 tries. Final gate on the EXACT committed HEAD.

---

## CONFIRMED API SYMBOLS (verified against the repo — use exactly)

- **`AdminCommands.bid`** (`infrastructure/commands/AdminCommands.kt:158-176`) — `@Subcommand("bid")` currently annotated **`@Permission("enthusiamarket.admin")`**. Body: casts sender to Player (else `command.players_only`), calls `auctionService.placeBid(AuctionId(auction), player.uniqueId, amount)`, branches `AuctionResult.{Success,Failure,NotFound}`. **Only the perm annotation changes.**
- **Permission node `enthusiamarket.auction.bid`** — **already declared** in `build.gradle.kts:197`: `node("enthusiamarket.auction.bid", default = Default.TRUE, description = "Bid in an auction")`. No new node needed; it generates into `paper-plugin.yml` via nexus-permissions. (`enthusiamarket.stall.bid` at :179 is the older/unused sibling — do NOT use it.)
- **`RentCollectionService.processStall(stall: Stall, now: Instant): ProcessResult`** (`application/RentCollectionService.kt:91+`) — first statement is the owner-type guard. `tick(now: Instant = Instant.now())` filters to `activeStates` then calls `processStall` per stall. `ProcessResult.{Collected,Defaulted,Evicted,Skipped}`. The success branch sets `nextRentAt = now.plus(collectionInterval())`; OWNED→GRACE keeps the old (past) `nextRentAt`; eviction sets it null. `Stall.nextRentAt: Instant?`.
- **`RentCollectionServiceTest`** (`src/test/kotlin/.../RentCollectionServiceTest.kt`) — `now = Instant.parse("2026-05-24T10:00:00Z")`, `ownedStall` fixture (`nextRentAt` unset → null), `buildService(stalls, economyWithdrawOk, gracePeriod)` returns `ServiceWithMocks(service, stallRepo, economy, config)`, `tick(now)` is called with the fixed `now`. Mirror the existing "collects rent from OWNED stall" test for structure.
- **detekt:** `processStall` already carries `@Suppress("LongMethod", "CyclomaticComplexMethod")`; a one-line early-return guard stays within that.

---

## Task 1: C-15 — let players bid (perm node correction)

**Files:**
- Modify: `src/main/kotlin/net/badgersmc/em/infrastructure/commands/AdminCommands.kt`

Not TDD — this is a one-line framework-metadata fix; the node already exists and is `Default.TRUE`.

- [ ] **Step 1: Change the annotation**

On the `bid` subcommand (AdminCommands.kt:159), change:
```kotlin
    @Subcommand("bid")
    @Permission("enthusiamarket.admin")
```
to:
```kotlin
    @Subcommand("bid")
    @Permission("enthusiamarket.auction.bid")
```
Change nothing else. (`/em bid <auction> <amount>` is the documented player bid path; the browser GUI remains view-only for now — wiring click-to-bid is a separate enhancement, NOT in this PR.)

- [ ] **Step 2: Verify build + the annotation**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew detekt compileKotlin generateNexusPermissions -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain && grep -n "enthusiamarket.auction.bid" src/main/kotlin/net/badgersmc/em/infrastructure/commands/AdminCommands.kt`
Expected: BUILD SUCCESSFUL, detekt 0, and the grep prints the `@Permission("enthusiamarket.auction.bid")` line.

- [ ] **Step 3: Commit**
```bash
cd /d/BadgersMC-Dev/EnthusiaMarket && git add src/main/kotlin/net/badgersmc/em/infrastructure/commands/AdminCommands.kt
git commit -m "fix(auction): /em bid uses player perm enthusiamarket.auction.bid (C-15)

Non-ops could not bid; the command was gated on enthusiamarket.admin while
the player node enthusiamarket.auction.bid already existed (default TRUE).

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 2: C-10 — rent ticker honours future nextRentAt

**Files:**
- Modify: `src/main/kotlin/net/badgersmc/em/application/RentCollectionService.kt`
- Test: `src/test/kotlin/net/badgersmc/em/application/RentCollectionServiceTest.kt`

- [ ] **Step 1: Failing tests**

Add to `RentCollectionServiceTest` (mirror the existing OWNED-collect test):
```kotlin
    @Test fun `tick skips OWNED stall whose nextRentAt is in the future`() {
        val prepaid = ownedStall.copy(nextRentAt = now.plus(Duration.ofDays(1)))
        val svc = buildService(stalls = listOf(prepaid), economyWithdrawOk = true)
        val report = svc.service.tick(now)
        assertEquals(0, report.collected)
        assertEquals(0, report.defaults)
        assertEquals(0, report.evictions)
        verify(exactly = 0) { svc.economy.withdraw(any(), any()) }
        verify(exactly = 0) { svc.stallRepo.save(any()) }
    }

    @Test fun `tick charges OWNED stall whose nextRentAt is due`() {
        val due = ownedStall.copy(nextRentAt = now.minus(Duration.ofMinutes(1)))
        val svc = buildService(stalls = listOf(due), economyWithdrawOk = true)
        val report = svc.service.tick(now)
        assertEquals(1, report.collected)
        verify { svc.economy.withdraw(playerUuid, 50L) }
    }
```
(The existing "collects rent from OWNED stall" test uses an `ownedStall` with `nextRentAt == null` — keep it; the null case must still charge. See Step 3.)

- [ ] **Step 2: Run — verify RED**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew test --tests "net.badgersmc.em.application.RentCollectionServiceTest" -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: the new "skips … future" test FAILS (current code charges regardless of `nextRentAt`); the "due" + null-case tests pass.

- [ ] **Step 3: Add the guard**

As the **first statement** of `processStall` (above the owner-type guard, so it applies to every owner type and stays independent of the #40 guild branch), add:
```kotlin
        // C-10: honour a future nextRentAt. Buyout/auction-settle/extension push
        // nextRentAt forward to pre-pay a period; without this guard the fixed-interval
        // ticker re-charges on its own schedule and the pre-paid period is lost.
        // A null nextRentAt (legacy/seeded stalls) falls through and is charged as before.
        stall.nextRentAt?.let { due -> if (now.isBefore(due)) return ProcessResult.Skipped }
```
Note: GRACE stalls keep their old (past) `nextRentAt`, so the guard does NOT skip them — the grace→evict retry path is unchanged.

- [ ] **Step 4: Run — verify GREEN**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew test --tests "net.badgersmc.em.application.RentCollectionServiceTest" -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: PASS (new + all existing rent tests, including the null-nextRentAt collect and the grace/evict cases).

- [ ] **Step 5: Commit**
```bash
cd /d/BadgersMC-Dev/EnthusiaMarket && git add src/main/kotlin/net/badgersmc/em/application/RentCollectionService.kt src/test/kotlin/net/badgersmc/em/application/RentCollectionServiceTest.kt
git commit -m "fix(rent): ticker skips stalls with a future nextRentAt (C-10)

Voluntary extension and fresh buyout push nextRentAt forward to pre-pay a
period, but the fixed-interval ticker re-charged regardless, losing the
pre-paid period (double-charge). Guard: skip when now < nextRentAt; null
nextRentAt still charges; GRACE keeps its past nextRentAt so eviction is
unaffected.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 3: Final gate

- [ ] **Step 1: Full verification on committed HEAD**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew clean detekt test shadowJar -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: BUILD SUCCESSFUL, detekt 0, all tests pass, shadowJar built.

- [ ] **Step 2: Report** — final gate output + the 2 commit hashes. Do NOT push.

---

## Self-Review Notes (for the implementer)
1. **C-15 is metadata only** — do not touch the bid body, do not add a node (it exists). Don't "improve" by wiring GUI bidding here; that's a separate PR.
2. **C-10 guard placement is deliberate** — first line of `processStall`, before owner-type branching, so it composes with the #40 guild charge branch and never skips GRACE (GRACE's `nextRentAt` is in the past).
3. **Null nextRentAt must still charge** — the `?.let` falls through on null; the existing OWNED-collect test (null fixture) is the guard against a regression here.
4. **Branch off post-#40 main.** If #40 isn't merged yet, STOP and report — do not re-implement guild rent.
