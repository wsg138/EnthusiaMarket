# Guild Disband Cleanup — Implementation Plan (audit M-16)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax.

**Goal:** When a LumaGuilds guild disbands, free its EnthusiaMarket assets. Today nothing happens: the guild's stalls keep `owner.type=GUILD` pointing at a defunct guild id (free stalls stay orphaned forever; rented ones only churn out slowly via rent eviction), and guild-bound shops stay bound. Two gaps cause this:
1. `GuildDisbandedEventListener` (Bukkit listener → `provider.handleDisbanded`) is **never registered** — it's a plain class, not a DI bean, so Phase-6 auto-registration skips it.
2. Even if it fired, **no `onDissolved` handler is registered**, so `handleDisbanded` iterates an empty handler list.

**Fix (settled in brainstorming):** on disband, **reset the guild's stalls to UNOWNED** (return them to the auction pool, reusing the admin-evict path) and **unbind its shops** (revert to personal/no-guild). Decision was "Reset to UNOWNED + cleanup," not reassign-to-disbander.

**Out of scope:** everything else from the audit. **Small PR.**

**Architecture:** Hexagonal/SPEAR. New application service (`GuildDissolutionService`, TDD) + make the existing Bukkit listener a bean + one wiring line in `onEnable`. Reuses `StallEvictionService.evict` (no duplicated eviction logic).

**Tech Stack:** Kotlin 2.0.0, Nexus DI (`@Service`/`@Component`, `ctx.getBean<T>()`), Phase-6 `registerNexusListeners`, JUnit 5 + MockK, detekt 1.23.8.

**Standing rules (every task):**
- Prefix bash with `cd <REPO> &&` (`/d/BadgersMC-Dev/EnthusiaMarket` or `/opt/data/EnthusiaMarket`). On Hermes' box also prefix gradle with `export JAVA_HOME=/opt/data/jdk-21.0.11+10 &&`.
- Every gradle command includes `-Plumaguilds.jar=<JAR> --no-daemon --console=plain`.
- LF→CRLF warnings expected. Branch `fix/guild-disband` off current `main`. Do not push (coordinator opens the PR).
- TDD on the service. Commit after every task.
- Gate rule: compare each `Run:` to `Expected:`; mismatch → STOP, fix, re-run; HALT after 3 tries. Final gate on the EXACT committed HEAD.

---

## CONFIRMED API SYMBOLS (verified against the repo — use exactly)

- **`StallEvictionService.evict(stallId: StallId): Result`** (`application/StallEvictionService.kt`, `@Service`) — resets an OWNED/GRACE stall to `UNOWNED` (`owner = OwnerRef.unowned()`, `members = emptySet()`, `winningBid = 0`, `nextRentAt = null`), clears WG owners/members, fires `StallStateChangedEvent`, restores schematic if enabled. Returns `Result.{Evicted,NotFound,NotOwned}`. **A non-OWNED/GRACE guild stall (e.g. mid-auction) returns `NotOwned` and is left alone — acceptable.** Reuse this; do NOT duplicate eviction logic.
- **`StallRepository`** (`domain/stall/StallRepository.kt`) — `all(): List<Stall>`, `findById`, `save`. No by-guild query exists; filter `all()`. `Stall.id: StallId`, `Stall.owner: OwnerRef` (`type: OwnerType`, `id: String`; for GUILD, `id` is the guild id string).
- **`ShopRepository`** (`domain/shop/ShopRepository.kt`) — `findByGuildId(guildId: UUID): List<Shop>`, `removeGuildOwnership(id: Long): Shop?` (sets `guild_id`/`creator_id` NULL; no actor check — correct for a system action). `Shop.id: Long`.
- **`GuildProvider`** (`domain/ports/GuildProvider.kt`) — `fun onDissolved(handler: (guildId: String) -> Unit)`. The id passed to handlers is `event.guild.id.toString()` (a UUID string), matching both `OwnerRef.id` for GUILD stalls and `UUID.fromString` for `findByGuildId`.
- **`LumaGuildsGuildProvider`** (`infrastructure/lumaguilds/LumaGuildsGuildProvider.kt`, `@Component`, implements `GuildProvider`) — has `internal fun handleDisbanded(guildId: String)` that invokes all registered `onDissolved` handlers (each already wrapped in try/catch). `onDissolved` impl appends to `dissolveHandlers`.
- **`GuildDisbandedEventListener`** (`infrastructure/lumaguilds/GuildDisbandedEventListener.kt`) — Bukkit `Listener`; `@EventHandler onGuildDisbanded(event: GuildDisbandedEvent)` calls `provider.handleDisbanded(event.guild.id.toString())`. Ctor takes the **concrete** `LumaGuildsGuildProvider` (needs the `internal` method). **Currently NOT a DI bean and NOT registered.**
- **Listener auto-registration** — `EnthusiaMarket.onEnable` Phase 6 calls `registerNexusListeners(basePackage = "net.badgersmc.em", …)`, which registers DI beans that are Bukkit `Listener`s. Proven by `SignPlaceListener` (annotated `@Component`, no `@Listener`). So annotating `GuildDisbandedEventListener` `@Component` is sufficient to get it registered — **provided its ctor dep (`LumaGuildsGuildProvider`) resolves as a bean, which it does (`@Component`).**
- **Bean resolution in onEnable** — `ctx.getBean<T>()` (e.g. `ctx.getBean<ParticleBorderService>()` at the bottom of `onEnable`) forces construction and returns the singleton. `ctx` (the Nexus context) is in scope through `onEnable`.
- **`OwnerType`** = `net.badgersmc.em.domain.stall.OwnerType { NONE, SOLO, GUILD }`.

---

## Task 1: GuildDissolutionService (the cleanup) — TDD

**Files:**
- Create: `src/main/kotlin/net/badgersmc/em/application/GuildDissolutionService.kt`
- Test: `src/test/kotlin/net/badgersmc/em/application/GuildDissolutionServiceTest.kt`

- [ ] **Step 1: Failing test**

```kotlin
class GuildDissolutionServiceTest {
    // mocks: StallRepository, StallEvictionService, ShopRepository
    @Test fun `handle evicts the guild's stalls and unbinds its shops`() {
        // stalls.all() returns: 2 GUILD stalls for guildId, 1 GUILD stall for OTHER guild, 1 SOLO stall
        // shops.findByGuildId(UUID(guildId)) returns 2 shops
        // call service.handle(guildId)
        // verify eviction.evict called for the 2 matching guild stalls only (NOT the other guild's, NOT SOLO)
        // verify shops.removeGuildOwnership called for both returned shops
    }
    @Test fun `handle with a corrupt guild id skips shop unbind but still scans stalls`() {
        // guildId = "not-a-uuid" → findByGuildId never called (can't parse); stalls.all() still filtered by string id
        // (no matching stalls in this fixture → no evict, no throw)
    }
    @Test fun `one failing eviction does not abort the rest`() {
        // eviction.evict(first) throws; verify eviction.evict(second) still called
    }
}
```

- [ ] **Step 2: Run — verify RED**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew test --tests "*GuildDissolutionServiceTest" -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: FAIL — class doesn't exist yet.

- [ ] **Step 3: Implement**

```kotlin
package net.badgersmc.em.application

import net.badgersmc.em.domain.shop.ShopRepository
import net.badgersmc.em.domain.stall.OwnerType
import net.badgersmc.em.domain.stall.StallRepository
import net.badgersmc.nexus.annotations.Service
import java.util.UUID
import java.util.logging.Logger

/**
 * Frees a disbanded guild's EnthusiaMarket assets: resets its stalls to UNOWNED
 * (reusing the admin-evict path) and unbinds its shops. Wired to
 * GuildProvider.onDissolved in EnthusiaMarket.onEnable. Best-effort and isolated —
 * one failing item must not abort the rest (disband cleanup can't be retried).
 */
@Service
class GuildDissolutionService(
    private val stalls: StallRepository,
    private val eviction: StallEvictionService,
    private val shops: ShopRepository,
) {
    private val log = Logger.getLogger(GuildDissolutionService::class.java.name)

    fun handle(guildId: String) {
        var evicted = 0
        stalls.all()
            .filter { it.owner.type == OwnerType.GUILD && it.owner.id == guildId }
            .forEach { stall ->
                try {
                    eviction.evict(stall.id)
                    evicted++
                } catch (e: Exception) {
                    log.warning("GuildDissolutionService: evict failed for stall ${stall.id.value}: ${e.message}")
                }
            }

        var unbound = 0
        val gid = runCatching { UUID.fromString(guildId) }.getOrNull()
        if (gid != null) {
            shops.findByGuildId(gid).forEach { shop ->
                try {
                    shops.removeGuildOwnership(shop.id)
                    unbound++
                } catch (e: Exception) {
                    log.warning("GuildDissolutionService: unbind failed for shop ${shop.id}: ${e.message}")
                }
            }
        }
        log.info("Guild $guildId disbanded: $evicted stall(s) freed, $unbound shop(s) unbound.")
    }
}
```

- [ ] **Step 4: Run — verify GREEN**

Run: same as Step 2. Expected: PASS (all 3 cases).

- [ ] **Step 5: Commit**
```bash
cd /d/BadgersMC-Dev/EnthusiaMarket && git add src/main/kotlin/net/badgersmc/em/application/GuildDissolutionService.kt src/test/kotlin/net/badgersmc/em/application/GuildDissolutionServiceTest.kt
git commit -m "feat(guild): GuildDissolutionService — free stalls + unbind shops on disband (M-16)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 2: Register the disband listener + wire the handler

**Files:**
- Modify: `src/main/kotlin/net/badgersmc/em/infrastructure/lumaguilds/GuildDisbandedEventListener.kt`
- Modify: `src/main/kotlin/net/badgersmc/em/EnthusiaMarket.kt`

Not TDD — DI/Bukkit wiring; verified by build + reasoning (matches C-15-style metadata wiring).

- [ ] **Step 1: Make the listener a bean**

In `GuildDisbandedEventListener.kt`, annotate the class `@net.badgersmc.nexus.annotations.Component` (add the import) so Phase-6 `registerNexusListeners` registers it with Bukkit. Keep the ctor `(provider: LumaGuildsGuildProvider)` — it needs the concrete type's `internal handleDisbanded`. Change nothing else.

- [ ] **Step 2: Register the onDissolved handler in onEnable**

In `EnthusiaMarket.onEnable`, AFTER Phase 6 (`registerNexusListeners`) — place it next to the existing `ctx.getBean<...>()` calls near the bottom — add:
```kotlin
        // M-16: on guild disband, free its stalls + unbind its shops.
        val guildDissolution = ctx.getBean<net.badgersmc.em.application.GuildDissolutionService>()
        ctx.getBean<net.badgersmc.em.domain.ports.GuildProvider>().onDissolved { guildId ->
            guildDissolution.handle(guildId)
        }
```
(`getBean<GuildDissolutionService>()` forces construction; `getBean<GuildProvider>()` returns the `LumaGuildsGuildProvider` singleton whose `handleDisbanded` the now-registered listener calls.)

- [ ] **Step 3: Verify build**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew detekt compileKotlin test -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: BUILD SUCCESSFUL, detekt 0, all tests pass. If the DI container fails to resolve `GuildDissolutionService` or `LumaGuildsGuildProvider` at runtime, that surfaces as a context/test failure — re-read the bean-resolution symbols, don't guess.

- [ ] **Step 4: Commit**
```bash
cd /d/BadgersMC-Dev/EnthusiaMarket && git add src/main/kotlin/net/badgersmc/em/infrastructure/lumaguilds/GuildDisbandedEventListener.kt src/main/kotlin/net/badgersmc/em/EnthusiaMarket.kt
git commit -m "feat(guild): register disband listener + onDissolved cleanup handler (M-16)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 3: Final gate

- [ ] **Step 1: Full verification on committed HEAD**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew clean detekt test shadowJar -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: BUILD SUCCESSFUL, detekt 0, all tests pass, shadowJar built.

- [ ] **Step 2: Report** — gate output + the 2 commit hashes. Do NOT push.

---

## Self-Review Notes (for the implementer)
1. **Reuse `StallEvictionService.evict`** — do not re-implement UNOWNED reset / WG clear / schematic restore. It already handles all of it and fires the state event.
2. **String vs UUID** — guild stalls match by `owner.id == guildId` (string); shops use `UUID.fromString(guildId)`. A corrupt id skips only the shop step.
3. **Best-effort** — wrap each evict/unbind in try/catch so one failure doesn't strand the rest; disband cleanup isn't retried.
4. **Mid-auction guild stalls** are left as-is by `evict` (NotOwned) — acceptable; the auction resolves normally.
5. **Two beans must resolve** — `GuildDissolutionService` (`@Service`) and `LumaGuildsGuildProvider` (`@Component`, ctor dep of the listener). Both are scanned beans; the explicit `getBean` in onEnable forces the service. If a resolution error appears, fix the wiring — don't widen scope.
