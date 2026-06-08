# Audit Batch 1 — Pre-Tag Fixes (full-audit #4, v3 limit criticals)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Fix the three verified real bugs from the v3 + full audits (the rest were false positives, theoretical, by-design, or post-tag polish). All small, all disjoint files.

1. **#4 — guild-shop unbind auth** (`ShopGuildService.unregisterGuildShop`): the gate is `guildProvider.isMember(...)` (mere roster presence). Any rankless guild member can revert any guild-owned shop to personal ownership, redirecting guild revenue. Fix: require `MANAGE_SHOPS`.
2. **v3-A — limit per-kind cap bypass** (`LimitResolutionService.canClaim`): `if (limits.isUnlimited) return Allowed` short-circuits the per-kind check when `total == -1`. A player with unlimited *total* but a finite *kind* cap bypasses the kind cap. Fix: delete the early return; the per-dimension `>= 0` guards below already handle unlimited correctly.
3. **v3-B — limit total default** (`EnthusiaMarketConfig.LimitGroup.total`): defaults to `0` though the comment says "-1 = unlimited". A group configuring only `regionkinds` (omitting `total`) silently **rejects every claim** for its members. Fix: default `total = -1`.

**Out of scope:** auction hardening (penny-bid/self-bid — separate batch), the `require()`-validation minor sweep, everything else. Small PR.

**Architecture:** Hexagonal/SPEAR. One application-service fix, one policy-logic fix, one config default. TDD the two logic fixes.

**Tech Stack:** Kotlin 2.0.0, Nexus DI, JUnit 5 + MockK, detekt 1.23.8.

**Standing rules:**
- Prefix bash with `cd <REPO> &&`. On Hermes' box prefix gradle with `export JAVA_HOME=/opt/data/jdk-21.0.11+10 &&`; repo `/opt/data/EnthusiaMarket`, jar `/opt/data/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar`.
- Every gradle command includes `-Plumaguilds.jar=<JAR> --no-daemon --console=plain`.
- LF→CRLF warnings expected. Branch `fix/audit-batch1` off current `main`. Do not push (coordinator opens the PR; pushes go to fork `fork` only).
- TDD on Tasks 1–2. Commit after every task.
- Gate rule: compare each `Run:` to `Expected:`; mismatch → STOP, fix, re-run; HALT after 3 tries.

---

## CONFIRMED API SYMBOLS (verified against the repo)

- **`GuildProvider`** (`domain/ports/GuildProvider.kt`): `isMember(player: UUID, guildId: String): Boolean`, `hasShopPermission(player: UUID, guildId: String, permission: GuildPermission): Boolean`, `enum GuildPermission { …, MANAGE_SHOPS, … }`. Usage precedent: `StallBuyoutService.buyForGuild` calls `guildProvider.hasShopPermission(actor, guild.id, GuildProvider.GuildPermission.MANAGE_SHOPS)`.
- **`ShopGuildService.unregisterGuildShop(shopId: Long, actor: UUID): Result<Shop>`** (`application/ShopGuildService.kt`): injects `guildProvider`. Current gate:
  ```kotlin
  val isShopOwner = shop.owner == actor
  val isGuildMember = guildProvider.isMember(actor, shop.guildId.toString())
  if (!isShopOwner && !isGuildMember) { return Result.failure(IllegalAccessException(...)) }
  ```
- **`LimitResolutionService.canClaim(player, kind, currentTotal, currentForKind): ClaimDecision`** (`application/LimitResolutionService.kt`): currently
  ```kotlin
  val limits = effectiveLimits(player)
  if (limits.isUnlimited) return ClaimDecision.Allowed      // <-- the bug (skips kind cap)
  if (limits.total >= 0 && currentTotal >= limits.total) return ...TotalCapReached(limits.total)
  val kindCap = limits.regionkinds[kind]
  if (kindCap != null && kindCap >= 0 && currentForKind >= kindCap) return ...KindCapReached(kind, kindCap)
  return ClaimDecision.Allowed
  ```
  `EffectiveLimits.isUnlimited get() = total < 0`. The two `>= 0` guards already make the unlimited case fall through to `Allowed` correctly, so the early return is redundant + harmful.
- **`EnthusiaMarketConfig.LimitGroup`** (`config/EnthusiaMarketConfig.kt`): `var total: Int = 0` (comment: "-1 = unlimited"), `var regionkinds: MutableMap<String, Int>`.
- Test files exist: `ShopGuildServiceTest.kt`, `LimitResolutionServiceTest.kt`.

---

## Task 1: #4 — require MANAGE_SHOPS to unbind a guild shop

**Files:** `application/ShopGuildService.kt`, `test/.../ShopGuildServiceTest.kt`

- [ ] **Step 1: Failing test** — in `ShopGuildServiceTest`, add: a member WITHOUT `MANAGE_SHOPS` (and not the shop owner) calling `unregisterGuildShop` → `Result.failure`; a member WITH `MANAGE_SHOPS` → success. Mock `guildProvider.hasShopPermission(actor, guildId, GuildProvider.GuildPermission.MANAGE_SHOPS)` true/false. Keep the existing shop-owner-can-unregister test green.
- [ ] **Step 2: RED** — `./gradlew test --tests "*ShopGuildServiceTest" -Plumaguilds.jar=… --no-daemon --console=plain` → the no-perm-member case currently PASSES the unbind (so the new "rejected" assertion fails). 
- [ ] **Step 3: Fix** — replace:
  ```kotlin
  val isGuildMember = guildProvider.isMember(actor, shop.guildId.toString())
  if (!isShopOwner && !isGuildMember) {
  ```
  with:
  ```kotlin
  val hasManagePerm = guildProvider.hasShopPermission(
      actor, shop.guildId.toString(), GuildProvider.GuildPermission.MANAGE_SHOPS
  )
  if (!isShopOwner && !hasManagePerm) {
  ```
  Update the failure message wording ("owner nor a MANAGE_SHOPS member"). Add the `GuildProvider` import if needed (likely already present).
- [ ] **Step 4: GREEN** — same command passes.
- [ ] **Step 5: Commit** — `fix(guild): require MANAGE_SHOPS to unbind a guild shop (audit #4)` + Co-Authored-By trailer.

## Task 2: v3-A — limit per-kind cap no longer bypassed when total is unlimited

**Files:** `application/LimitResolutionService.kt`, `test/.../LimitResolutionServiceTest.kt`

- [ ] **Step 1: Failing test** — add: player whose effective limits are `total = -1` (unlimited) AND `regionkinds = {"premium": 1}`, calling `canClaim(player, "premium", currentTotal = 5, currentForKind = 1)` → expect `ClaimDecision.Rejected.KindCapReached("premium", 1)` (currently returns `Allowed`). Set this up via the perms/config the test already uses to drive `effectiveLimits` (mirror an existing test). Keep the existing "fully unlimited → Allowed" test green.
- [ ] **Step 2: RED** — `./gradlew test --tests "*LimitResolutionServiceTest" …` → new test fails (returns Allowed).
- [ ] **Step 3: Fix** — delete the line `if (limits.isUnlimited) return ClaimDecision.Allowed`. Leave the two `>= 0`-guarded checks; they already return `Allowed` for a fully-unlimited player.
- [ ] **Step 4: GREEN** — same command passes (new + fully-unlimited + existing).
- [ ] **Step 5: Commit** — `fix(limits): unlimited total no longer bypasses per-kind cap (audit v3-A)` + trailer.

## Task 3: v3-B — LimitGroup.total defaults to unlimited

**Files:** `config/EnthusiaMarketConfig.kt` (+ optional config test)

- [ ] **Step 1: Fix** — change `var total: Int = 0` to `var total: Int = -1` in `LimitGroup` (matches the "-1 = unlimited" comment; omitting `total` now means "no total cap" instead of "reject everything").
- [ ] **Step 2: Verify** — if a `LimitResolutionServiceTest`/config test asserts the old default, update it; add/extend a test that a group with only `regionkinds` set (default `total`) does NOT reject a first claim. Run `./gradlew test --tests "*LimitResolutionServiceTest" …` green.
- [ ] **Step 3: Commit** — `fix(limits): LimitGroup.total defaults to -1 unlimited, not 0 reject-all (audit v3-B)` + trailer.

## Task 4: Final gate
- [ ] `./gradlew clean detekt test shadowJar -Plumaguilds.jar=… --no-daemon --console=plain` → BUILD SUCCESSFUL, detekt 0, tests pass, shadowJar built.
- [ ] Report gate output + the 3 commit hashes. Do NOT push to BadgersMC; if pushing, push branch to `fork` only.

## Self-Review Notes
1. **#4**: only the auth predicate changes; `registerGuildShop` (owner-gated) and `removeGuildOwnership` are untouched.
2. **v3-A**: deleting the early return is the whole fix — do not add new branches. The bypass-node and no-group paths set `total = -1`, which the `>= 0` guards already pass through to `Allowed`.
3. **v3-B**: a pure default change; the only risk is a test that hard-codes the old `0` default — update it, don't revert the fix.
