# Limits + Market Regions Implementation Plan (ItemShops Parity SP4)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Complete the per-player stall-ownership limit system. Fix the no-group reject-all bug, gate the buyout/sign-purchase path, wire real `stall.kind` into the auction gate, add `/em limit`, and declare the bypass node. Limits count **SOLO-owned stalls only** (guild stalls excluded).

**Architecture:** Hexagonal/SPEAR. The engine (`LimitResolutionService`) exists and stays Bukkit-free. Add one `StallOwnershipCounter` + gate insertions + a read-only command.

**Tech Stack:** Kotlin 2.0.0, Nexus DI + commands, JUnit 5 + MockK + MockBukkit, detekt 1.23.8.

**Reference spec:** `docs/superpowers/specs/2026-06-04-itemshops-parity-limits-regions-design.md`

**Standing rules (every task):**
- Prefix bash with `cd <REPO> &&` (`/d/BadgersMC-Dev/EnthusiaMarket`, or `/opt/data/EnthusiaMarket`). On Hermes' box also prefix gradle with `export JAVA_HOME=/opt/data/jdk-21.0.11+10 &&`.
- Every gradle command includes `-Plumaguilds.jar=<JAR> --no-daemon --console=plain`.
- LF→CRLF git warnings expected. Branch `feat/limits-regions`. Do not push (coordinator opens the PR).
- TDD: write failing test, run RED, then GREEN. Commit after every task with the given message.
- Gate rule: compare each `Run:` to `Expected:`; mismatch → STOP, fix, re-run; HALT after 3 tries. Final gate runs on the EXACT committed HEAD.

---

## CONFIRMED API SYMBOLS (verified against the repo — use exactly)

- **`LimitResolutionService`** (`application/`) — `class(config: EnthusiaMarketConfig, perms: PermissionChecker)`. `effectiveLimits(player: UUID): EffectiveLimits` (data class `total: Int`, `regionkinds: Map<String,Int>`, `isUnlimited get() = total < 0`). `canClaim(player: UUID, kind: String, currentTotal: Int, currentForKind: Int): ClaimDecision`. `ClaimDecision`: `Allowed`, `Rejected.TotalCapReached(cap)`, `Rejected.KindCapReached(kind, cap)`. Companion: `UNLIMITED = -1`, `LIMIT_PREFIX = "enthusiamarket.limit."`, `BYPASS_NODE = "enthusiamarket.admin.bypasslimit"`. **effectiveLimits currently starts `total = 0` and returns it when no group matches — the Task 1 bug.**
- **`EnthusiaMarketConfig.limits`** — `MutableMap<String, LimitGroup>` (ships **empty** `mutableMapOf()`). `LimitGroup { total: Int (-1 unlimited); regionkinds: MutableMap<String,Int> }`.
- **`StallRepository.all(): List<Stall>`** (used in `AuctionLifecycleService`). `findById(StallId): Stall?`.
- **`Stall`** (`domain/stall/`) — `owner: OwnerRef`, `kind: String` (default `"default"`). **`OwnerRef`** (`domain/stall/`) — `type: OwnerType`, `id: String`. **`OwnerType { NONE, SOLO, GUILD }`**. `OwnerRef.solo(uuid)`, `OwnerRef.guild(id)`.
- **`StallBuyoutService`** (`application/`) — `@Service`, ctor `(stalls: StallRepository, offers: SellOfferRepository, auctions: AuctionRepository, economy: EconomyProvider, config: EnthusiaMarketConfig, guildProvider: GuildProvider, regionMembers: RegionMemberSync)`. **Add `limits: LimitResolutionService, counter: StallOwnershipCounter`.** `sealed interface Result { Purchased(stall, price, owner); NotFound; AuctionLive; AlreadyOwned; NotInGuild; NoGuildPermission; Rejected(reason: String) }`. `buy(stallId, buyer, price)` → `buyForOwner(stallId, payer=buyer, owner=OwnerRef.solo(buyer), price)`; `buyForGuild(...)` → `buyForOwner(..., owner=OwnerRef.guild(guildId), ...)`. **`private fun buyForOwner(stallId, payer: UUID, owner: OwnerRef, price): Result`** — fetches `val stall = stalls.findById(stallId) ?: return Result.NotFound`, then `AuctionLive`/`AlreadyOwned` guards, then charges. **The limit gate goes after those guards, before the charge, and only when `owner.type == OwnerType.SOLO`** (so guild buys skip it).
- **`Result.Rejected(reason: String)`** is rendered by `PurchaseSignClickListener` as `lang.msg("purchase_sign.msg.rejected", "reason" to result.reason)`. The repo convention is plain-English reason strings in the service (e.g. `SellOfferService`: `Result.Rejected("Insufficient funds: $total required")`). **Limit rejections follow this** — return `Result.Rejected("…")`; no new reject lang keys.
- **`AuctionLifecycleService`** (`application/`) — injects `stallRepository: StallRepository` and `limits: LimitResolutionService`. In the settle path: `val stall = stallRepository.findById(auction.stallId)` (already resolved, ~L343); then an **inline** count `val winnerOwnedCount = stallRepository.all().count { it.owner.type == OwnerType.SOLO && it.owner.id == bid.bidder.toString() }` (~L351) and `limits.canClaim(player = bid.bidder, kind = DEFAULT_KIND, currentTotal = winnerOwnedCount, currentForKind = winnerOwnedCount)` (~L355). **Task 3** replaces the inline count with `counter.counts(bid.bidder)` and `DEFAULT_KIND` with the resolved `stall.kind` (+ kind-restricted count). The `DEFAULT_KIND` companion const becomes unused — remove it.
- **`AdminCommands`** (`infrastructure/commands/`) — `@Command(name="em")`, multi-segment `@Subcommand`s, `@Context sender: CommandSender`, `@Permission`. Inject `LimitResolutionService` + `StallOwnershipCounter` for `/em limit`. `lang.msg("key", "tok" to v)`.
- **`PermissionChecker`** (`domain/ports/`) — `has(player: UUID, node: String): Boolean`. Used by `LimitResolutionService`; tests mock it.
- **Permission DSL** (`build.gradle.kts`) — `node("name", default = Default.OP, ...)`; admin nodes nearby. `Default` = `net.badgersmc.nexus.permissions.Default`.
- **Lang** (`en_US.yml`) — top-level `admin:` block has `import`/`reload`/`evict` (the `/em` messages). Add a `limit:` sub-block under it (or a top-level `limit:`); `<token>` placeholders.

---

## Task 1: Fix effectiveLimits — no group = unlimited

**Files:**
- Modify: `src/main/kotlin/net/badgersmc/em/application/LimitResolutionService.kt`
- Test: `src/test/kotlin/net/badgersmc/em/application/LimitResolutionServiceTest.kt` (extend existing)

- [ ] **Step 1: Write the failing regression test**

Add to the existing `LimitResolutionServiceTest` (read it first for the mock setup — `mockk<PermissionChecker>`, `mockk<EnthusiaMarketConfig>`):

```kotlin
    @Test fun `player in no granted group is unlimited`() {
        val cfg = EnthusiaMarketConfig().apply {
            limits["vip"] = EnthusiaMarketConfig.LimitGroup().apply { total = 3 }
        }
        val perms = mockk<net.badgersmc.em.domain.ports.PermissionChecker> {
            every { has(any(), any()) } returns false // holds neither bypass nor any limit group
        }
        val svc = LimitResolutionService(cfg, perms)
        val player = java.util.UUID.randomUUID()
        assertTrue(svc.effectiveLimits(player).isUnlimited)
        assertEquals(
            LimitResolutionService.ClaimDecision.Allowed,
            svc.canClaim(player, "default", currentTotal = 99, currentForKind = 99),
        )
    }

    @Test fun `empty config leaves everyone unlimited`() {
        val perms = mockk<net.badgersmc.em.domain.ports.PermissionChecker> { every { has(any(), any()) } returns false }
        val svc = LimitResolutionService(EnthusiaMarketConfig(), perms)
        assertTrue(svc.effectiveLimits(java.util.UUID.randomUUID()).isUnlimited)
    }
```

- [ ] **Step 2: Run — verify it fails**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew test --tests "net.badgersmc.em.application.LimitResolutionServiceTest" -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: FAIL — the two new tests fail (currently `total = 0`, not unlimited).

- [ ] **Step 3: Fix `effectiveLimits`**

Track whether any group applied; if none did, return unlimited:

```kotlin
    fun effectiveLimits(player: UUID): EffectiveLimits {
        if (perms.has(player, bypassNode)) {
            return EffectiveLimits(total = UNLIMITED, regionkinds = emptyMap())
        }
        var matched = false
        var total = 0
        val regionkinds = mutableMapOf<String, Int>()
        for ((name, group) in config.limits) {
            if (!perms.has(player, "$LIMIT_PREFIX$name")) continue
            matched = true
            total = mergeBest(total, group.total)
            for ((kind, cap) in group.regionkinds) {
                regionkinds.merge(kind, cap, ::mergeBest)
            }
        }
        // No configured group applies to this player → no cap (limits only bind explicitly-grouped players).
        if (!matched) return EffectiveLimits(total = UNLIMITED, regionkinds = emptyMap())
        return EffectiveLimits(total, regionkinds.toMap())
    }
```

- [ ] **Step 4: Run — verify it passes** (whole test class, no regression)

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew test --tests "net.badgersmc.em.application.LimitResolutionServiceTest" -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: PASS (incl. the existing tests). If an existing test asserted `total = 0` for no-group, update it to the new unlimited semantics (that test encoded the bug).

- [ ] **Step 5: Commit**

```bash
cd /d/BadgersMC-Dev/EnthusiaMarket && git add src/main/kotlin/net/badgersmc/em/application/LimitResolutionService.kt src/test/kotlin/net/badgersmc/em/application/LimitResolutionServiceTest.kt
git commit -m "fix(limits): no applicable group means unlimited, not capped at 0 (SP4)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 2: StallOwnershipCounter

**Files:**
- Create: `src/main/kotlin/net/badgersmc/em/application/StallOwnershipCounter.kt`
- Test: `src/test/kotlin/net/badgersmc/em/application/StallOwnershipCounterTest.kt`

- [ ] **Step 1: Failing test**

```kotlin
package net.badgersmc.em.application

import io.mockk.every
import io.mockk.mockk
import net.badgersmc.em.domain.stall.OwnerRef
import net.badgersmc.em.domain.stall.Stall
import net.badgersmc.em.domain.stall.StallRepository
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class StallOwnershipCounterTest {

    private val player = UUID.randomUUID()

    private fun stall(owner: OwnerRef, kind: String) = mockk<Stall> {
        every { this@mockk.owner } returns owner
        every { this@mockk.kind } returns kind
    }

    @Test fun `counts SOLO-owned by the player, grouped by kind, excluding guild and others`() {
        val repo = mockk<StallRepository> {
            every { all() } returns listOf(
                stall(OwnerRef.solo(player), "default"),
                stall(OwnerRef.solo(player), "default"),
                stall(OwnerRef.solo(player), "farm"),
                stall(OwnerRef.guild("g1"), "default"),          // guild — excluded
                stall(OwnerRef.solo(UUID.randomUUID()), "default"), // someone else — excluded
            )
        }
        val c = StallOwnershipCounter(repo).counts(player)
        assertEquals(3, c.total)
        assertEquals(2, c.byKind["default"])
        assertEquals(1, c.byKind["farm"])
    }

    @Test fun `zero for a player who owns none`() {
        val repo = mockk<StallRepository> { every { all() } returns emptyList() }
        val c = StallOwnershipCounter(repo).counts(player)
        assertEquals(0, c.total)
        assertEquals(emptyMap(), c.byKind)
    }
}
```

- [ ] **Step 2: Run — verify it fails.**
Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew test --tests "net.badgersmc.em.application.StallOwnershipCounterTest" -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: FAIL — `StallOwnershipCounter` not defined.

- [ ] **Step 3: Implement**

```kotlin
package net.badgersmc.em.application

import net.badgersmc.em.domain.stall.OwnerType
import net.badgersmc.em.domain.stall.StallRepository
import net.badgersmc.nexus.annotations.Service
import java.util.UUID

/** Counts a player's personally-owned (SOLO) stalls, total and per region kind (ItemShops parity SP4). */
@Service
class StallOwnershipCounter(private val stalls: StallRepository) {

    data class OwnedCounts(val total: Int, val byKind: Map<String, Int>)

    fun counts(player: UUID): OwnedCounts {
        val owned = stalls.all().filter {
            it.owner.type == OwnerType.SOLO && it.owner.id == player.toString()
        }
        return OwnedCounts(total = owned.size, byKind = owned.groupingBy { it.kind }.eachCount())
    }
}
```

- [ ] **Step 4: Run — verify it passes.** (same command) Expected: PASS

- [ ] **Step 5: Commit**

```bash
cd /d/BadgersMC-Dev/EnthusiaMarket && git add src/main/kotlin/net/badgersmc/em/application/StallOwnershipCounter.kt src/test/kotlin/net/badgersmc/em/application/StallOwnershipCounterTest.kt
git commit -m "feat(limits): StallOwnershipCounter — SOLO stalls total + by kind (SP4)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 3: Wire counter + real stall.kind into the auction gate

**Files:**
- Modify: `src/main/kotlin/net/badgersmc/em/application/AuctionLifecycleService.kt`

- [ ] **Step 1: Inject the counter + use real kind**

Read the settle region (~L340–365). Add `private val ownership: StallOwnershipCounter,` to the constructor. Replace the inline `winnerOwnedCount` count + the `DEFAULT_KIND` arg:

```kotlin
        val counts = ownership.counts(bid.bidder)
        val decision = limits.canClaim(
            player = bid.bidder,
            kind = stall.kind,
            currentTotal = counts.total,
            currentForKind = counts.byKind[stall.kind] ?: 0,
        )
```

(`stall` is the already-resolved `stallRepository.findById(auction.stallId)` in scope. Remove the now-unused `DEFAULT_KIND` companion const and the old inline-count placeholder comment.)

- [ ] **Step 2: Build + run the auction tests**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew test --tests "net.badgersmc.em.application.AuctionLifecycleService*" compileKotlin compileTestKotlin -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: BUILD SUCCESSFUL + PASS. If an `AuctionLifecycleService` test constructs the service directly, add a `mockk<StallOwnershipCounter>(relaxed = true)` (or stub `counts(...)`) arg. If a test asserted the old per-kind no-op, update it to the real-kind behaviour.

- [ ] **Step 3: Commit**

```bash
cd /d/BadgersMC-Dev/EnthusiaMarket && git add src/main/kotlin/net/badgersmc/em/application/AuctionLifecycleService.kt src/test/kotlin/net/badgersmc/em/application/AuctionLifecycleService*
git commit -m "feat(limits): auction gate uses StallOwnershipCounter + real stall.kind (SP4)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 4: Gate the buyout / sign-purchase path

**Files:**
- Modify: `src/main/kotlin/net/badgersmc/em/application/StallBuyoutService.kt`
- Test: `src/test/kotlin/net/badgersmc/em/application/StallBuyoutServiceTest.kt` (extend existing)

- [ ] **Step 1: Inject limits + counter; gate buyForOwner (SOLO only)**

Add `private val limits: LimitResolutionService,` and `private val ownership: StallOwnershipCounter,` to the constructor. In `buyForOwner`, after the `AlreadyOwned`/`AuctionLive` guards and **before** the economy charge, insert:

```kotlin
        // Personal-ownership limit gate. Guild buys route here with owner.type == GUILD and skip it
        // (a guild claim is not a personal claim). Counts SOLO-owned stalls only.
        if (owner.type == net.badgersmc.em.domain.stall.OwnerType.SOLO) {
            val counts = ownership.counts(payer)
            when (val decision = limits.canClaim(payer, stall.kind, counts.total, counts.byKind[stall.kind] ?: 0)) {
                is LimitResolutionService.ClaimDecision.Rejected.TotalCapReached ->
                    return Result.Rejected("Stall limit reached (${decision.cap})")
                is LimitResolutionService.ClaimDecision.Rejected.KindCapReached ->
                    return Result.Rejected("Limit reached for ${decision.kind} stalls (${decision.cap})")
                LimitResolutionService.ClaimDecision.Allowed -> Unit
            }
        }
```

- [ ] **Step 2: Failing test**

Extend `StallBuyoutServiceTest` (read it for the existing construction + mocks). Add a case where the payer is at their total cap → `buy(...)` returns `Result.Rejected` and `economy.withdraw` is **never** called; and a case under the cap → proceeds to `Result.Purchased`. Stub `limits.canClaim(...)` to return `TotalCapReached(n)` / `Allowed`, and `ownership.counts(...)` accordingly (both are mockable services). Use `verify(exactly = 0) { economy.withdraw(any(), any()) }` on the rejection path.

- [ ] **Step 3: Run RED → confirm → GREEN**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew test --tests "net.badgersmc.em.application.StallBuyoutServiceTest" -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: PASS (was RED before the gate).

- [ ] **Step 4: Commit**

```bash
cd /d/BadgersMC-Dev/EnthusiaMarket && git add src/main/kotlin/net/badgersmc/em/application/StallBuyoutService.kt src/test/kotlin/net/badgersmc/em/application/StallBuyoutServiceTest.kt
git commit -m "feat(limits): gate personal stall buyout on canClaim (SP4)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 5: /em limit command + bypass node + lang

**Files:**
- Modify: `src/main/kotlin/net/badgersmc/em/infrastructure/commands/AdminCommands.kt`
- Modify: `build.gradle.kts`
- Modify: `src/main/resources/lang/en_US.yml`

- [ ] **Step 1: Permission node**

In `build.gradle.kts`, near the `enthusiamarket.admin*` nodes:
```kotlin
        node("enthusiamarket.admin.bypasslimit", default = Default.OP, description = "Bypass all stall-ownership limits")
```

- [ ] **Step 2: Lang keys**

In `en_US.yml`, add a `limit:` block (under the top-level `admin:` block is fine — match its indent):
```yaml
  limit:
    header: "<gold>Your stall limits"
    total: "<gray>Total: <white><used></white> / <yellow><cap>"
    kind: "<gray>  <white><kind></white>: <white><used></white> / <yellow><cap>"
    unlimited: "<green>∞"
```

- [ ] **Step 3: Inject + add the subcommand**

In `AdminCommands`, add `private val limits: net.badgersmc.em.application.LimitResolutionService,` and `private val ownership: net.badgersmc.em.application.StallOwnershipCounter,` to the constructor. Add:

```kotlin
    @Subcommand("limit")
    @Permission("enthusiamarket.stall.info")
    fun limitInfo(@Context sender: CommandSender) {
        val player = sender as? Player ?: run { sender.sendMessage(lang.msg("shop.cmd.players_only")); return }
        val eff = limits.effectiveLimits(player.uniqueId)
        val counts = ownership.counts(player.uniqueId)
        player.sendMessage(lang.msg("admin.limit.header"))
        val totalCap = capLabel(eff.total)
        player.sendMessage(lang.msg("admin.limit.total", "used" to counts.total, "cap" to totalCap))
        for ((kind, cap) in eff.regionkinds) {
            player.sendMessage(lang.msg("admin.limit.kind", "kind" to kind, "used" to (counts.byKind[kind] ?: 0), "cap" to capLabel(cap)))
        }
    }

    private fun capLabel(cap: Int): String =
        if (cap < 0) net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
            .serialize(lang.msg("admin.limit.unlimited"))
        else cap.toString()
```

(Confirm the lang path prefix — if the `limit:` block is nested under top-level `admin:`, the keys are `admin.limit.*` as above. Read the file to place the block + confirm the prefix. `Player` is `org.bukkit.entity.Player`, already imported in AdminCommands.)

- [ ] **Step 4: Build (+ fix AdminCommands test ctor if present)**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew compileKotlin compileTestKotlin -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: BUILD SUCCESSFUL. If an `AdminCommands` test constructs it directly, add `mockk<LimitResolutionService>(relaxed = true)` + `mockk<StallOwnershipCounter>(relaxed = true)`.

- [ ] **Step 5: Commit**

```bash
cd /d/BadgersMC-Dev/EnthusiaMarket && git add src/main/kotlin/net/badgersmc/em/infrastructure/commands/AdminCommands.kt build.gradle.kts src/main/resources/lang/en_US.yml
git commit -m "feat(limits): /em limit info + enthusiamarket.admin.bypasslimit node (SP4)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 6: Final gate

- [ ] **Step 1: Full verification on the committed HEAD**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew clean detekt test shadowJar -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: BUILD SUCCESSFUL, detekt 0, all tests pass.

- [ ] **Step 2: Mark progress + commit**

Append to `docs/tasks.md`: `- [x] ItemShops parity SP4 — limits + market regions (no-group=unlimited fix, buyout gate, real stall.kind, /em limit, bypass node)`. Then commit (`docs: mark ItemShops parity SP4 (limits/regions) complete`).

- [ ] **Step 3: Report**

Report the final gate output + commit list. Do NOT push.

---

## Self-Review Notes (for the implementer)

1. **Task 1 is the linchpin** — without the no-group=unlimited fix, gating the buyout path (Task 4) would reject every purchase on a default (empty-`limits`) server. Do it first; its regression test is the proof.
2. **Counting is SOLO-only everywhere** — `StallOwnershipCounter` filters `owner.type == SOLO && owner.id == player.toString()`. Guild stalls are excluded, satisfying "guild stalls don't count toward a personal limit." Auction (Task 3) and buyout (Task 4) both go through it.
3. **The buyout gate is conditioned on `owner.type == SOLO`** (Task 4) — `buyForOwner` is shared by `buy` (SOLO) and `buyForGuild` (GUILD); the condition skips guild buys. Don't gate `buy`/`buyForGuild` separately.
4. **Limit rejections use `Result.Rejected(plainString)`** (Task 4) — matches the existing `SellOfferService`/`StallBuyoutService` convention; rendered via `purchase_sign.msg.rejected`. No new reject lang keys.
5. **Place the gate before the charge** (Task 4) — after `NotFound`/`AuctionLive`/`AlreadyOwned`, before `economy.withdraw`, so a rejected player is never charged.
6. **Constructor churn** — `StallBuyoutService`, `AuctionLifecycleService`, `AdminCommands` gain deps; update any direct-construction test with `mockk(relaxed = true)` args. Nexus injects them in production.
7. **Lang prefix** — confirm whether the new `limit:` block nests under the existing top-level `admin:` (→ keys `admin.limit.*`) by reading `en_US.yml`; use the matching prefix in `AdminCommands`.
