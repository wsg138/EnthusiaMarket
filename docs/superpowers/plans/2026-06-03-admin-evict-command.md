# Admin Evict Command Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Add `/em evict <stall>` — an admin force-unclaim that resets an owned stall to UNOWNED, clears WorldGuard owner/members, and fires the state-changed event. No refund (admin action). Fills the long-standing gap where the `enthusiamarket.admin.evict` permission existed but no command was wired.

**Architecture:** Hexagonal/SPEAR. A new pure-ish `StallEvictionService` (application) encapsulates the reset (mirroring the rent-default eviction in `RentCollectionService`), unit-tested with mockk. A thin `@Subcommand` on the existing `AdminCommands` calls it.

**Tech Stack:** Kotlin 2.0.0, Nexus DI + commands, JUnit 5 + MockK, detekt 1.23.8.

**Standing rules (every task):**
- Bash cwd resets — prefix every command with `cd <REPO> &&` (`/d/BadgersMC-Dev/EnthusiaMarket`, or `/opt/data/EnthusiaMarket` on Hermes' box).
- Every gradle command includes `-Plumaguilds.jar=<LUMAGUILDS_JAR> --no-daemon --console=plain` (`/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar`, or `/opt/data/...`).
- On Hermes' box, prefix gradle with `export JAVA_HOME=/opt/data/jdk-21.0.11+10 &&`.
- LF→CRLF git warnings are expected — ignore. Branch: `hotfix/admin-evict`. Do not push (coordinator opens the PR).
- TDD: write the failing test, run it RED, then implement GREEN. Commit after every task with the given message.
- Gate rule: after each `Run:` step compare to `Expected:`. Mismatch → STOP, fix, re-run; HALT after 3 attempts.

---

## CONFIRMED API SYMBOLS (verified against the repo — use exactly)

**`Stall`** (`net.badgersmc.em.domain.stall.Stall`): `.copy(state=, owner=, ownerSince=, winningBid=, members=, nextRentAt=)`; `id: StallId` (`stall.id.value` is the String).
**`StallId`** (`net.badgersmc.em.domain.stall.StallId`): `StallId(value: String)`.
**`StallState`** (`net.badgersmc.em.domain.stall.StallState`): `UNOWNED`, `OWNED`, `GRACE`, `AUCTIONING`, `RE_AUCTIONING`, `EMERGENCY_AUCTIONING`.
**`OwnerRef`** (`net.badgersmc.em.domain.stall.OwnerRef`): `OwnerRef.unowned()`.
**`StallRepository`**: `findById(id: StallId): Stall?`, `save(stall: Stall)`.
**`RegionMemberSync`** (`net.badgersmc.em.domain.ports.RegionMemberSync`): `clearOwnersAndMembers(world: String, regionId: String)`.
**`EnthusiaMarketConfig`**: `config.schematics.enabled: Boolean`.
**`SchematicService`** (`net.badgersmc.em.domain.ports.SchematicService`): `restore(stallId: String, world: String, regionId: String): SchematicService.Result`; result subtype `SchematicService.Result.Failure(val cause: Throwable)`; companion default `SchematicService.Disabled`.
**`StallStateChangedEvent`** (`net.badgersmc.em.events.StallStateChangedEvent`): ctor `(stallId: String, previous: StallState, current: StallState)`.
**Null-safe event fire pattern** (mirror `AuctionLifecycleService.fireStateChanged`): `org.bukkit.Bukkit.getServer()?.pluginManager?.callEvent(event)` — `getServer()` is null in unit tests, so no event fires and no NPE.
**Nexus command annotations** (already imported in `AdminCommands.kt`): `@Subcommand("...")`, `@Permission("...")`, `@Context sender: CommandSender`, `@Arg("name") x: String`.
**`AdminCommands`** already injects: `stalls: StallRepository`, `config: EnthusiaMarketConfig`, `regionMembers: RegionMemberSync`, `lang: LangService` — it does NOT inject `SchematicService`; the new service is injected instead.
**Permission** `enthusiamarket.admin.evict` is ALREADY generated (DSL `node("enthusiamarket.admin", ...) { child("evict") }` in `build.gradle.kts`). No build.gradle change needed.

---

## Task 1: StallEvictionService

**Files:**
- Create: `src/main/kotlin/net/badgersmc/em/application/StallEvictionService.kt`
- Test: `src/test/kotlin/net/badgersmc/em/application/StallEvictionServiceTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package net.badgersmc.em.application

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import net.badgersmc.em.config.EnthusiaMarketConfig
import net.badgersmc.em.domain.ports.RegionMemberSync
import net.badgersmc.em.domain.stall.OwnerRef
import net.badgersmc.em.domain.stall.OwnerType
import net.badgersmc.em.domain.stall.RentTerms
import net.badgersmc.em.domain.stall.Stall
import net.badgersmc.em.domain.stall.StallId
import net.badgersmc.em.domain.stall.StallRepository
import net.badgersmc.em.domain.stall.StallState
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class StallEvictionServiceTest {

    private fun ownedStall(state: StallState = StallState.OWNED) = Stall(
        id = StallId("stall1"), regionId = "stall1", world = "world",
        state = state, owner = OwnerRef.solo(UUID.randomUUID()),
        ownerSince = Instant.now(), winningBid = 1000L, rentTerms = RentTerms.formula(1.0),
        members = setOf(UUID.randomUUID()), nextRentAt = Instant.now(),
    )

    private fun service(repo: StallRepository, regions: RegionMemberSync): StallEvictionService {
        val config = mockk<EnthusiaMarketConfig>()
        val schem = mockk<EnthusiaMarketConfig.Schematics>()
        every { schem.enabled } returns false
        every { config.schematics } returns schem
        return StallEvictionService(repo, regions, config)
    }

    @Test fun `evict resets an owned stall to UNOWNED and clears WG`() {
        val repo = mockk<StallRepository>(relaxed = true)
        val regions = mockk<RegionMemberSync>(relaxed = true)
        every { repo.findById(StallId("stall1")) } returns ownedStall()
        val saved = slot<Stall>()
        every { repo.save(capture(saved)) } returns Unit

        val result = service(repo, regions).evict(StallId("stall1"))

        assertIs<StallEvictionService.Result.Evicted>(result)
        assertEquals(StallState.UNOWNED, saved.captured.state)
        assertEquals(OwnerType.NONE, saved.captured.owner.type)
        assertEquals(0L, saved.captured.winningBid)
        assertEquals(emptySet(), saved.captured.members)
        verify { regions.clearOwnersAndMembers("world", "stall1") }
    }

    @Test fun `evict returns NotFound for a missing stall`() {
        val repo = mockk<StallRepository>(relaxed = true)
        every { repo.findById(StallId("nope")) } returns null
        val result = service(repo, mockk(relaxed = true)).evict(StallId("nope"))
        assertIs<StallEvictionService.Result.NotFound>(result)
    }

    @Test fun `evict returns NotOwned for an already-unowned stall`() {
        val repo = mockk<StallRepository>(relaxed = true)
        every { repo.findById(StallId("stall1")) } returns ownedStall(state = StallState.UNOWNED)
        val result = service(repo, mockk(relaxed = true)).evict(StallId("stall1"))
        assertIs<StallEvictionService.Result.NotOwned>(result)
        verify(exactly = 0) { repo.save(any()) }
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew test --tests "net.badgersmc.em.application.StallEvictionServiceTest" -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: FAIL — `StallEvictionService` not defined.

- [ ] **Step 3: Implement StallEvictionService**

```kotlin
package net.badgersmc.em.application

import net.badgersmc.em.config.EnthusiaMarketConfig
import net.badgersmc.em.domain.ports.RegionMemberSync
import net.badgersmc.em.domain.ports.SchematicService
import net.badgersmc.em.domain.stall.OwnerRef
import net.badgersmc.em.domain.stall.StallId
import net.badgersmc.em.domain.stall.StallRepository
import net.badgersmc.em.domain.stall.StallState
import net.badgersmc.em.events.StallStateChangedEvent
import net.badgersmc.nexus.annotations.Service
import java.util.logging.Logger

/**
 * Admin force-unclaim of a stall (the `/em evict` command). Resets an OWNED or
 * GRACE stall to UNOWNED, strips WorldGuard owner/members, restores the pre-claim
 * geometry (REQ-271, when schematics are enabled), and fires StallStateChangedEvent.
 * No refund — this is an operator action, mirroring the rent-default eviction in
 * RentCollectionService. Bound shops are intentionally NOT wiped (matches rent
 * eviction; use /em sellback for the shop-wiping owner flow).
 */
@Service
class StallEvictionService(
    private val stalls: StallRepository,
    private val regionMembers: RegionMemberSync,
    private val config: EnthusiaMarketConfig,
    private val schematics: SchematicService = SchematicService.Disabled,
) {
    private val log = Logger.getLogger(StallEvictionService::class.java.name)

    sealed interface Result {
        /** Stall was owned and is now UNOWNED. */
        data object Evicted : Result
        data object NotFound : Result
        /** Stall was not in an owned state (already UNOWNED / auctioning). */
        data object NotOwned : Result
    }

    @Suppress("TooGenericExceptionCaught")
    fun evict(stallId: StallId): Result {
        val stall = stalls.findById(stallId) ?: return Result.NotFound
        if (stall.state != StallState.OWNED && stall.state != StallState.GRACE) {
            return Result.NotOwned
        }
        val previous = stall.state
        stalls.save(
            stall.copy(
                state = StallState.UNOWNED,
                owner = OwnerRef.unowned(),
                ownerSince = null,
                winningBid = 0L,
                members = emptySet(),
                nextRentAt = null,
            )
        )
        try {
            regionMembers.clearOwnersAndMembers(stall.world, stall.regionId)
        } catch (e: Exception) {
            // DB is authoritative; WG can be resynced via /em rg resync.
            log.warning("Evict: WG owner/member clear failed for ${stall.id.value}: ${e.message}")
        }
        fireStateChanged(stall.id.value, previous, StallState.UNOWNED)
        if (config.schematics.enabled) {
            val restore = schematics.restore(stall.id.value, stall.world, stall.regionId)
            if (restore is SchematicService.Result.Failure) {
                log.warning(
                    "Evict: schematic restore failed for ${stall.id.value}; " +
                        "geometry left as-is. cause=${restore.cause.message}"
                )
            }
        }
        return Result.Evicted
    }

    @Suppress("TooGenericExceptionCaught")
    private fun fireStateChanged(stallId: String, previous: StallState, current: StallState) {
        try {
            org.bukkit.Bukkit.getServer()?.pluginManager?.callEvent(
                StallStateChangedEvent(stallId, previous, current)
            )
        } catch (e: Exception) {
            log.warning("Evict: failed to fire StallStateChangedEvent for $stallId: ${e.message}")
        }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew test --tests "net.badgersmc.em.application.StallEvictionServiceTest" -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
cd /d/BadgersMC-Dev/EnthusiaMarket && git add src/main/kotlin/net/badgersmc/em/application/StallEvictionService.kt src/test/kotlin/net/badgersmc/em/application/StallEvictionServiceTest.kt
git commit -m "feat(admin): StallEvictionService — force-unclaim an owned stall

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 2: `/em evict <stall>` subcommand + lang

**Files:**
- Modify: `src/main/kotlin/net/badgersmc/em/infrastructure/commands/AdminCommands.kt`
- Modify: `src/main/resources/lang/en_US.yml`

- [ ] **Step 1: Add lang keys**

In `en_US.yml`, under the `admin:` block, add an `evict:` sub-block (use `<token>` syntax, NEVER `{token}`):

```yaml
  evict:
    success: "<prefix><green>Evicted</green> <aqua><stall></aqua> <green>— reset to unowned and WG cleared."
    not_found: "<prefix><red>Stall not found:</red> <stall>"
    not_owned: "<prefix><yellow>Stall</yellow> <aqua><stall></aqua> <yellow>is not currently owned."
```

- [ ] **Step 2: Inject StallEvictionService + add the subcommand**

In `AdminCommands.kt`, add `private val stallEviction: net.badgersmc.em.application.StallEvictionService,` to the constructor (anywhere in the param list, e.g. after `sellback`). Then add the subcommand method (place it near the other `stall`/admin subcommands):

```kotlin
    @Subcommand("evict")
    @Permission("enthusiamarket.admin.evict")
    fun evict(
        @Context sender: CommandSender,
        @Arg("stall") stall: String,
    ) {
        val msg = when (stallEviction.evict(StallId(stall))) {
            is net.badgersmc.em.application.StallEvictionService.Result.Evicted ->
                lang.msg("admin.evict.success", "stall" to stall)
            is net.badgersmc.em.application.StallEvictionService.Result.NotFound ->
                lang.msg("admin.evict.not_found", "stall" to stall)
            is net.badgersmc.em.application.StallEvictionService.Result.NotOwned ->
                lang.msg("admin.evict.not_owned", "stall" to stall)
        }
        sender.sendMessage(msg)
    }
```

`StallId` is already imported in `AdminCommands.kt`. If the class has a `@Suppress("TooManyFunctions")` already (it should), no detekt change is needed; if detekt flags TooManyFunctions on the class, add `"TooManyFunctions"` to the existing class-level `@Suppress(...)`.

- [ ] **Step 3: Build + fix any AdminCommands test constructor breakage**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew compileKotlin compileTestKotlin -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: BUILD SUCCESSFUL. If an `AdminCommandsTest` (or `AdminCommandsEntityLimitTest`) constructs `AdminCommands(...)` directly, add a `mockk<net.badgersmc.em.application.StallEvictionService>(relaxed = true)` argument in the matching constructor position. Read the test first to place it correctly.

- [ ] **Step 4: Commit**

```bash
cd /d/BadgersMC-Dev/EnthusiaMarket && git add src/main/kotlin/net/badgersmc/em/infrastructure/commands/AdminCommands.kt src/main/resources/lang/en_US.yml src/test/kotlin/net/badgersmc/em/infrastructure/commands/AdminCommandsTest.kt src/test/kotlin/net/badgersmc/em/infrastructure/commands/AdminCommandsEntityLimitTest.kt
git commit -m "feat(admin): /em evict <stall> force-unclaim command

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

(If a test file wasn't modified, drop it from the `git add` — only add files you changed.)

---

## Task 3: Final gate

- [ ] **Step 1: Full verification**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew clean detekt test shadowJar -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: BUILD SUCCESSFUL, detekt 0 issues, all tests pass.

- [ ] **Step 2: Confirm the evict perm is in the staged descriptor**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && grep -E "admin.evict|enthusiamarket.admin:" build/resources/main/paper-plugin.yml`
Expected: `enthusiamarket.admin.evict` present (it was already generated; this just confirms no regression).

- [ ] **Step 3: Report**

Report the final gate output + commit list. Do NOT push.

---

## Self-Review Notes (for the implementer)

1. **`EnthusiaMarketConfig.Schematics`** mock (Task 1 test) — the config exposes `schematics: Schematics` with `enabled: Boolean`. The test mocks `config.schematics.enabled returns false` so no Bukkit/schematic path runs. If the nested class name differs, read `EnthusiaMarketConfig.kt` and adjust the mock type.
2. **`OwnerRef.solo(UUID)`** (test builder) — confirm the factory name by reading `OwnerRef.kt` (it has `unowned()` + a solo factory; if it's `OwnerRef.solo(uuid)` use that, else construct directly).
3. **AdminCommands constructor position** — append `stallEviction` so existing positional test constructions are easiest to fix; read the test before editing.
