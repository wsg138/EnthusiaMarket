# Guild Rent Collection Implementation Plan (audit M11)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Collect rent on GUILD-owned stalls from the guild bank (both automatic collection and voluntary extension), with the same default → grace → eviction flow as personal stalls. Closes audit finding M11 (guild stalls were rent-free forever).

**Architecture:** Hexagonal/SPEAR. Grace/eviction is state-based and reused unchanged; only the charge source branches by owner type.

**Tech Stack:** Kotlin 2.0.0, Nexus DI, JUnit 5 + MockK + MockBukkit, detekt 1.23.8.

**Reference spec:** `docs/superpowers/specs/2026-06-04-guild-rent-collection-design.md`

**Standing rules (every task):**
- Prefix bash with `cd <REPO> &&` (`/d/BadgersMC-Dev/EnthusiaMarket`, or `/opt/data/EnthusiaMarket`). On Hermes' box also prefix gradle with `export JAVA_HOME=/opt/data/jdk-21.0.11+10 &&`.
- Every gradle command includes `-Plumaguilds.jar=<JAR> --no-daemon --console=plain`.
- LF→CRLF git warnings expected. Branch `feat/guild-rent`. Do not push (coordinator opens the PR).
- TDD: write failing test, run RED, then GREEN. Commit after every task with the given message.
- Gate rule: compare each `Run:` to `Expected:`; mismatch → STOP, fix, re-run; HALT after 3 tries. Final gate runs on the EXACT committed HEAD.

---

## CONFIRMED API SYMBOLS (verified against the repo — use exactly)

- **`GuildProvider`** (`domain/ports/GuildProvider.kt`) — `bankBalance(guildId: String): Long`, `bankWithdraw(guildId: String, amount: Long): Boolean`, `bankDeposit(guildId: String, amount: Long): Boolean`. Returns `false` on insufficient/failure (does not throw for the balance case).
- **`OwnerRef`** (`domain/stall/`) — `type: OwnerType`, `id: String`. For a GUILD stall, `owner.id` **is the guild id string** (pass directly to the bank methods). `OwnerType { NONE, SOLO, GUILD }`.
- **`Stall`** — `owner: OwnerRef`, `winningBid: Long`, `rentTerms.dailyRent(winningBid): Long`, `state: StallState`, `nextRentAt`, `ownerSince`.
- **`RentCollectionService`** (`application/RentCollectionService.kt`) — `@Service`, ctor `(stallRepository: StallRepository, offers: SellOfferRepository, economy: EconomyProvider, config: EnthusiaMarketConfig, regionMembers: RegionMemberSync, schematics: SchematicService = SchematicService.Disabled)`. **Add `guildProvider: GuildProvider`** (import `net.badgersmc.em.domain.ports.GuildProvider`). `processStall(stall, now): ProcessResult` currently starts with `if (stall.owner.type != OwnerType.SOLO) return ProcessResult.Skipped`, then resolves `ownerUuid` (invalid UUID → Skipped), computes the **M4-floored** `rentDue` (`if (winningBid > 0L) maxOf(computed, 1L) else computed`), then `val withdrawSuccess = economy.withdraw(ownerUuid, rentDue)`. The success/failure branches below it (`Collected`, OWNED→GRACE `Defaulted`, GRACE→evict `Evicted`, offer cleanup, `regionMembers.clearOwnersAndMembers`, schematic restore) are **owner-type-agnostic — do not touch them**. Method already carries `@Suppress("LongMethod", "CyclomaticComplexMethod")`.
- **`ProcessResult`** — `Collected`, `Defaulted`, `Evicted`, `Skipped` (sealed/enum in the same file).
- **`StallRentExtensionService`** (`application/StallRentExtensionService.kt`) — `@Service`, **already injects `guildProvider: GuildProvider`** and gates on `stall.canManage(actor, guildProvider)`. In `extend`: `if (!economy.withdraw(actor, amount)) return Result.Rejected("Insufficient funds: $amount required")`, then a `try { stalls.save(...) } catch (e) { <refund via economy.deposit(actor, amount), boolean-checked, rethrow e> }`. **Branch both the charge and the refund** by `stall.owner.type`. Add `import net.badgersmc.em.domain.stall.OwnerType`.
- **detekt:** `@Suppress` on a method is honoured; Codacy is **not** in this repo's CI for this branch but watch method length/complexity — prefer small private helpers over big inline `when`s if a method grows.

---

## Task 1: RentCollectionService — guild-bank charge branch

**Files:**
- Modify: `src/main/kotlin/net/badgersmc/em/application/RentCollectionService.kt`
- Test: `src/test/kotlin/net/badgersmc/em/application/RentCollectionServiceTest.kt` (extend existing)

- [ ] **Step 1: Failing tests**

Read `RentCollectionServiceTest` first for its construction + mock pattern (it builds the service with mocks; add a `mockk<GuildProvider>` and pass it as the new ctor arg in every construction). Add:

```kotlin
    @Test fun `guild stall rent is charged to the guild bank`() {
        // OWNED guild stall, bank has funds → Collected, nextRentAt advanced.
        // every { guildProvider.bankWithdraw("guild1", any()) } returns true
        // assert ProcessResult.Collected and stallRepository.save(...) with advanced nextRentAt
    }

    @Test fun `guild stall with empty bank defaults to GRACE`() {
        // OWNED guild stall, bankWithdraw returns false → ProcessResult.Defaulted, state GRACE
    }

    @Test fun `guild stall past grace is evicted`() {
        // GRACE guild stall, ownerSince past grace window, bankWithdraw false → Evicted, owner cleared
    }
```
Use `OwnerRef.guild("guild1")` (or the project's guild owner-ref constructor) for the stall owner; `every { guildProvider.bankWithdraw("guild1", any()) } returns true/false` as the case requires. (Read an existing SOLO rent test to mirror the stall fixture + assertions.)

- [ ] **Step 2: Run — verify it fails**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew test --tests "net.badgersmc.em.application.RentCollectionServiceTest" -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: FAIL — guild stalls are currently Skipped (no bankWithdraw), and the ctor lacks `guildProvider`.

- [ ] **Step 3: Inject GuildProvider + branch the charge**

Add `private val guildProvider: GuildProvider,` to the constructor (import it). In `processStall`, replace the leading skip + ownerUuid resolution + `economy.withdraw` line with:

```kotlin
        // Unowned/NONE stalls have nothing to charge.
        if (stall.owner.type == OwnerType.NONE) return ProcessResult.Skipped

        // M4: floor to >= 1 for stalls with a real buy price; admin-gifted (winningBid <= 0) stay free.
        val computed = stall.rentTerms.dailyRent(stall.winningBid)
        val rentDue = if (stall.winningBid > 0L) maxOf(computed, 1L) else computed

        val withdrawSuccess = when (stall.owner.type) {
            OwnerType.SOLO -> {
                // Corrupt owner id → skip (don't evict on bad data), matching the prior behaviour.
                val ownerUuid = runCatching { UUID.fromString(stall.owner.id) }.getOrNull()
                    ?: return ProcessResult.Skipped
                rentDue <= 0L || economy.withdraw(ownerUuid, rentDue)
            }
            // GUILD: owner.id is the guild id; rent draws the guild bank.
            OwnerType.GUILD -> rentDue <= 0L || guildProvider.bankWithdraw(stall.owner.id, rentDue)
            OwnerType.NONE -> return ProcessResult.Skipped // unreachable (guarded above)
        }
```

Leave everything after `val withdrawSuccess = …` (the success/failure/grace/evict branches) exactly as-is.

- [ ] **Step 4: Run — verify it passes (no SOLO regression)**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew test --tests "net.badgersmc.em.application.RentCollectionServiceTest" -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: PASS (new guild tests + all existing SOLO tests). If an existing test constructs `RentCollectionService` directly, add the `guildProvider` mock arg.

- [ ] **Step 5: Commit**

```bash
cd /d/BadgersMC-Dev/EnthusiaMarket && git add src/main/kotlin/net/badgersmc/em/application/RentCollectionService.kt src/test/kotlin/net/badgersmc/em/application/RentCollectionServiceTest.kt
git commit -m "feat(rent): collect guild-stall rent from the guild bank (M11)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 2: StallRentExtensionService — guild-bank charge + refund

**Files:**
- Modify: `src/main/kotlin/net/badgersmc/em/application/StallRentExtensionService.kt`
- Test: `src/test/kotlin/net/badgersmc/em/application/StallRentExtensionServiceTest.kt` (extend existing, if present; else create)

- [ ] **Step 1: Failing tests**

Add guild-stall cases to the extension test (read the existing SOLO test for the fixture/mocks; the service already takes `guildProvider`):
```kotlin
    @Test fun `guild stall extension draws the guild bank`() {
        // OWNED guild stall, actor canManage, bankWithdraw true → Result.Extended; verify bankWithdraw called, not economy.withdraw
    }
    @Test fun `guild stall extension with empty bank is rejected`() {
        // bankWithdraw false → Result.Rejected
    }
```

- [ ] **Step 2: Run — verify it fails**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew test --tests "net.badgersmc.em.application.StallRentExtensionServiceTest" -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: FAIL — guild stalls currently charge the actor via `economy.withdraw`.

- [ ] **Step 3: Branch charge + refund by owner type**

Add `import net.badgersmc.em.domain.stall.OwnerType`. Replace the charge:
```kotlin
        val isGuild = stall.owner.type == OwnerType.GUILD
        val charged = if (isGuild) guildProvider.bankWithdraw(stall.owner.id, amount)
                      else economy.withdraw(actor, amount)
        if (!charged) {
            return Result.Rejected(
                if (isGuild) "The guild bank has insufficient funds: $amount required"
                else "Insufficient funds: $amount required"
            )
        }
```
In the persist-failure `catch`, branch the refund deposit (keep the boolean check + rethrow of the original `e`):
```kotlin
            val refunded = try {
                if (isGuild) guildProvider.bankDeposit(stall.owner.id, amount)
                else economy.deposit(actor, amount)
            } catch (refund: Exception) {
                log.severe("StallRentExtensionService.extend: refund of $amount (guild=$isGuild) threw: ${refund.message}")
                false
            }
            // ... existing refunded ? "refunded" : "manual refund required" logging, then throw e
```

- [ ] **Step 4: Run — verify it passes**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew test --tests "net.badgersmc.em.application.StallRentExtensionServiceTest" -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: PASS (guild + existing SOLO).

- [ ] **Step 5: Commit**

```bash
cd /d/BadgersMC-Dev/EnthusiaMarket && git add src/main/kotlin/net/badgersmc/em/application/StallRentExtensionService.kt src/test/kotlin/net/badgersmc/em/application/StallRentExtensionServiceTest.kt
git commit -m "feat(rent): guild-stall rent extension draws the guild bank (M11)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 3: Final gate + close M11

- [ ] **Step 1: Full verification on the committed HEAD**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew clean detekt test shadowJar -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: BUILD SUCCESSFUL, detekt 0, all tests pass. If `processStall` newly trips a detekt rule that its existing `@Suppress` doesn't cover, extract the charge `when` into a private `chargeRent(stall, rentDue): Boolean?` helper (null = skip) rather than widening suppressions.

- [ ] **Step 2: Mark progress + commit**

Append to `docs/tasks.md`: `- [x] Audit M11 — guild-stall rent collection (guild bank charge + grace/eviction; extension draws the bank)`. Then commit (`docs: mark audit M11 (guild rent) complete`).

- [ ] **Step 3: Report** — final gate output + commit list. Do NOT push.

---

## Self-Review Notes (for the implementer)

1. **Grace/eviction is shared** — only the *charge source* differs. Do not duplicate or alter the success/failure branches in `processStall`; feed the branched boolean into them.
2. **SOLO behaviour must not change** — corrupt SOLO owner id still → `Skipped` (not evict); `rentDue <= 0` still → collected (no charge). The existing SOLO tests are the guard.
3. **`owner.id` is the guild id** for GUILD stalls — pass it straight to `bankWithdraw`/`bankDeposit`.
4. **No refund in auto-collection** — intentionally mirrors the pre-existing SOLO path (out of scope). Only the voluntary `extend` path refunds, and that branch goes to the bank.
5. **Constructor churn** — `RentCollectionService` gains `guildProvider`; update any direct-construction test (`mockk<GuildProvider>(relaxed = true)`). `StallRentExtensionService` already has it.
