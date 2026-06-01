# EM Release Readiness Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship EnthusiaMarket for next-week release by fixing the WorldGuard region-provisioning gap (27/71 stalls unbuildable), the permission-declaration drift bug, the Bedrock shop-creation item-serialization corruption bug, and wiring the shipped-but-dead features (entity limits, region info card, particle outline, shop creation/edit menus).

**Architecture:** Hexagonal/SPEAR — domain ports, application services, infrastructure adapters. New outbound port `RegionProvisioner` (WG flag/priority writer) mirrors the existing `RegionMemberSync` pattern. Permission tree moves from hand-maintained `paper-plugin.yml` to a build-time Kotlin DSL. Entity-limit kind is a stored column on `Stall` resolved in the spawn hot-path.

**Tech Stack:** Kotlin 2.0.0, Gradle Kotlin DSL + Shadow, Nexus DI (@Service/@Component/@Repository), WorldGuard 7.0.9 API, WorldEdit 7.3.0, JUnit 5 + MockK + MockBukkit, JDBC migrations via nexus-persistence, detekt 1.23.8.

**Reference spec:** `docs/superpowers/specs/2026-06-01-em-release-readiness-design.md`

**Standing rules for every task:**
- Bash cwd resets between calls — prefix commands with `cd /d/BadgersMC-Dev/EnthusiaMarket &&`.
- LumaGuilds jar must be passed: `-Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar`.
- Full verify command: `./gradlew detekt test shadowJar -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
- SPEAR cycle per task: write failing test (red) → minimal impl (green) → check layer boundaries → refine → commit.
- Op-bypass warning: WG `op-permissions: true`. Any manual in-game test of build rights MUST use a non-op account or the bug is masked. Automated tests use mocks/MockBukkit so this only affects manual QA.

---

## Workstream 0 — Config fixes (no code)

### Task 0.1: Fix regionPrefix and add stallPriority

**Files:**
- Modify: `src/main/kotlin/net/badgersmc/em/config/EnthusiaMarketConfig.kt` (Market class, ~line 91-96)

- [ ] **Step 1: Change regionPrefix default and add stallPriority**

In `EnthusiaMarketConfig.kt`, replace the `Market` class body:

```kotlin
    class Market {
        @Comment("World name where stall regions exist")
        var world: String = "world"
        @Comment("WorldGuard region prefix for stall detection (production regions are stall1..stall71, no underscore)")
        var regionPrefix: String = "stall"
        @Comment(
            "WorldGuard priority stamped on stall regions during /em import. " +
                "Must exceed the surrounding safezone priority (market=10, spawn=10) " +
                "so stall member build-rights override the safezone deny."
        )
        var stallPriority: Int = 20
    }
```

- [ ] **Step 2: Update ImportStallsServiceTest mock prefix to match new default**

The existing test mocks `listByPrefix("world", "stall_")`. This still works (it passes the prefix explicitly), so no change required yet — leave it. Verify compile only.

- [ ] **Step 3: Build to confirm config compiles**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew compileKotlin -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
cd /d/BadgersMC-Dev/EnthusiaMarket && git add src/main/kotlin/net/badgersmc/em/config/EnthusiaMarketConfig.kt
git commit -m "fix(config): regionPrefix stall (no underscore) + add market.stallPriority

Production WG regions are named stall1..stall71. The old 'stall_' default
matched zero regions. Add stallPriority (default 20) for region provisioning.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Workstream F — Region build provisioning (TOP PRIORITY, release-blocking)

New SPEAR tasks TDD-280 (port + adapter), TDD-281 (import wiring).

**File structure:**
- Create: `src/main/kotlin/net/badgersmc/em/domain/ports/RegionProvisioner.kt` — outbound port.
- Create: `src/main/kotlin/net/badgersmc/em/infrastructure/worldguard/WorldGuardRegionProvisioner.kt` — WG adapter writing priority + flags.
- Modify: `src/main/kotlin/net/badgersmc/em/application/ImportStallsService.kt` — call provisioner per region.
- Modify: `src/main/resources/lang/en_US.yml` — provision count in import result message.
- Modify: `src/main/kotlin/net/badgersmc/em/infrastructure/commands/AdminCommands.kt` — surface provisioned count.
- Test: `src/test/kotlin/net/badgersmc/em/application/ImportStallsServiceTest.kt` — provisioner called per created region.

### Task F.1: RegionProvisioner port (TDD-280)

**Files:**
- Create: `src/main/kotlin/net/badgersmc/em/domain/ports/RegionProvisioner.kt`

- [ ] **Step 1: Write the port interface**

```kotlin
package net.badgersmc.em.domain.ports

/**
 * Outbound port that stamps a stall's WorldGuard region with the priority
 * and build/use/interact flags required for members to operate the stall
 * inside a surrounding safezone (market/spawn deny build by default).
 *
 * EM replaces ARM (Advanced Region Market); ARM is what configured the
 * legacy stalls' flags. This port lets EM take over that responsibility
 * so newly-carved and previously-unconfigured stalls become usable on
 * import without manual /rg setup. See spec §3 Workstream F.
 *
 * Implementations live in infrastructure (see WorldGuardRegionProvisioner).
 * Idempotent: re-provisioning an already-correct region is a no-op write.
 */
interface RegionProvisioner {
    /**
     * Set [regionId]'s priority to [priority] and apply the standard stall
     * flag set (build/use/chest-access/block-place/block-break/ride scoped
     * to MEMBERS, use to ALL, plus item-frame-rotation + interact for
     * decoration entities). Silently no-ops when the world or region is
     * not loaded — the import caller logs counts.
     *
     * @return true if the region was found and provisioned, false if the
     *         world/region was missing.
     */
    fun provision(world: String, regionId: String, priority: Int): Boolean
}
```

- [ ] **Step 2: Build to confirm the port compiles**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew compileKotlin -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
cd /d/BadgersMC-Dev/EnthusiaMarket && git add src/main/kotlin/net/badgersmc/em/domain/ports/RegionProvisioner.kt
git commit -m "feat(domain): add RegionProvisioner port (TDD-280)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

### Task F.2: WorldGuard adapter for RegionProvisioner (TDD-280)

WorldGuard 7.0.9 flag API reference (verified against existing WG usage in repo):
- Flags live in `com.sk89q.worldguard.protection.flags.Flags` (static fields: `BUILD`, `USE`, `CHEST_ACCESS`, `BLOCK_PLACE`, `BLOCK_BREAK`, `RIDE`, `INTERACT`, `ITEM_FRAME_ROTATE`).
- `StateFlag` values: `com.sk89q.worldguard.protection.flags.StateFlag.State.ALLOW`.
- Region-group flags: `flag.regionGroupFlag` returns a `RegionGroupFlag`; values from `com.sk89q.worldguard.protection.flags.RegionGroup` (`MEMBERS`, `ALL`).
- Set: `region.setFlag(Flags.BUILD, StateFlag.State.ALLOW)` and `region.setFlag(Flags.BUILD.regionGroupFlag, RegionGroup.MEMBERS)`.
- Priority: `region.priority = 20`.

**Files:**
- Create: `src/main/kotlin/net/badgersmc/em/infrastructure/worldguard/WorldGuardRegionProvisioner.kt`

- [ ] **Step 1: Write the adapter (mirrors WorldGuardRegionMemberSync withRegion pattern)**

```kotlin
package net.badgersmc.em.infrastructure.worldguard

import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldguard.WorldGuard
import com.sk89q.worldguard.protection.flags.Flags
import com.sk89q.worldguard.protection.flags.RegionGroup
import com.sk89q.worldguard.protection.flags.StateFlag
import com.sk89q.worldguard.protection.regions.ProtectedRegion
import net.badgersmc.em.domain.ports.RegionProvisioner
import net.badgersmc.nexus.annotations.Component
import org.bukkit.Bukkit
import java.util.logging.Logger

/**
 * WorldGuard implementation of [RegionProvisioner]. Stamps the stall
 * region with the ARM-equivalent flag set (core build/use/chest/place/
 * break/ride scoped to MEMBERS + use to ALL) plus decoration flags
 * (item-frame-rotation, interact) so item frames and armor stands work.
 * See spec §3 Workstream F.
 */
@Component
class WorldGuardRegionProvisioner : RegionProvisioner {

    private val log = Logger.getLogger(javaClass.name)

    override fun provision(world: String, regionId: String, priority: Int): Boolean {
        val bukkitWorld = Bukkit.getWorld(world)
        if (bukkitWorld == null) {
            log.warning("RegionProvisioner: world $world not loaded; skipping $regionId")
            return false
        }
        val regionManager = WorldGuard.getInstance().platform.regionContainer
            .get(BukkitAdapter.adapt(bukkitWorld))
        if (regionManager == null) {
            log.warning("RegionProvisioner: no region manager for $world; skipping $regionId")
            return false
        }
        val region = regionManager.getRegion(regionId)
        if (region == null) {
            log.warning("RegionProvisioner: region $regionId not found in $world; skipping")
            return false
        }
        applyFlags(region, priority)
        return true
    }

    private fun applyFlags(region: ProtectedRegion, priority: Int) {
        region.priority = priority
        // Core build rights, scoped to region members.
        setMemberAllow(region, Flags.BUILD)
        setMemberAllow(region, Flags.CHEST_ACCESS)
        setMemberAllow(region, Flags.BLOCK_PLACE)
        setMemberAllow(region, Flags.BLOCK_BREAK)
        setMemberAllow(region, Flags.RIDE)
        // Use (buttons/doors/etc.) open to everyone so shoppers can interact.
        region.setFlag(Flags.USE, StateFlag.State.ALLOW)
        region.setFlag(Flags.USE.regionGroupFlag, RegionGroup.ALL)
        // Decoration entities: item frames + armor stands need these so the
        // entity-limit feature governs entities players can actually use.
        region.setFlag(Flags.ITEM_FRAME_ROTATE, StateFlag.State.ALLOW)
        region.setFlag(Flags.ITEM_FRAME_ROTATE.regionGroupFlag, RegionGroup.MEMBERS)
        region.setFlag(Flags.INTERACT, StateFlag.State.ALLOW)
        region.setFlag(Flags.INTERACT.regionGroupFlag, RegionGroup.MEMBERS)
    }

    private fun setMemberAllow(region: ProtectedRegion, flag: StateFlag) {
        region.setFlag(flag, StateFlag.State.ALLOW)
        region.setFlag(flag.regionGroupFlag, RegionGroup.MEMBERS)
    }
}
```

- [ ] **Step 2: Build to confirm WG flag API references resolve**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew compileKotlin -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: BUILD SUCCESSFUL. If a flag field name is wrong (e.g. `ITEM_FRAME_ROTATE`), the compiler names the unresolved symbol — check `com.sk89q.worldguard.protection.flags.Flags` for the exact constant and fix.

- [ ] **Step 3: Commit**

```bash
cd /d/BadgersMC-Dev/EnthusiaMarket && git add src/main/kotlin/net/badgersmc/em/infrastructure/worldguard/WorldGuardRegionProvisioner.kt
git commit -m "feat(worldguard): WG adapter for RegionProvisioner (TDD-280)

Stamps priority + core build flags (MEMBERS) + use (ALL) + decoration
flags (item-frame-rotation, interact) replicating the ARM flag set.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

### Task F.3: Wire provisioning into import (TDD-281)

`ImportStallsService` gains a `RegionProvisioner` dependency and a `stallPriority` parameter, provisions every region it processes (both newly-created and pre-existing — idempotent ensures already-imported stalls get their flags fixed too), and reports the count.

**Files:**
- Modify: `src/main/kotlin/net/badgersmc/em/application/ImportStallsService.kt`
- Test: `src/test/kotlin/net/badgersmc/em/application/ImportStallsServiceTest.kt`

- [ ] **Step 1: Write the failing test for provisioning**

Add these tests to `ImportStallsServiceTest.kt`. First update the `service(...)` helper to inject a mock provisioner and the priority, then add two tests. Replace the existing `service` helper with:

```kotlin
    private fun service(
        regions: List<RegionProvider.RegionRef>,
        existing: List<Stall> = emptyList(),
        provisioner: net.badgersmc.em.domain.ports.RegionProvisioner =
            mockk(relaxed = true),
    ): Triple<ImportStallsService, StallRepository, net.badgersmc.em.domain.ports.RegionProvisioner> {
        val regionProvider = mockk<RegionProvider>()
        every { regionProvider.listByPrefix("world", "stall") } returns regions
        val repo = mockk<StallRepository>(relaxed = true)
        every { repo.findByRegion(any(), any()) } answers {
            val region = secondArg<String>()
            existing.firstOrNull { it.regionId == region }
        }
        val svc = ImportStallsService(
            regionProvider, repo, defaultRent = RentTerms.formula(1.0),
            provisioner = provisioner, stallPriority = 20,
        )
        return Triple(svc, repo, provisioner)
    }
```

Then update the two existing tests to use the new prefix `"stall"` and destructure a `Triple` (the third value can be ignored with `_`), and add:

```kotlin
    @Test fun `provisions every matched region on import`() {
        val provisioner = mockk<net.badgersmc.em.domain.ports.RegionProvisioner>(relaxed = true)
        val (svc, _, prov) = service(
            regions = listOf(
                RegionProvider.RegionRef("world", "stall1"),
                RegionProvider.RegionRef("world", "stall2"),
            ),
            provisioner = provisioner,
        )
        svc.import("world", "stall")
        verify { prov.provision("world", "stall1", 20) }
        verify { prov.provision("world", "stall2", 20) }
    }

    @Test fun `provisions even already-imported stalls so flags get fixed`() {
        val existing = Stall(
            id = StallId("stall1"), regionId = "stall1", world = "world",
            state = net.badgersmc.em.domain.stall.StallState.UNOWNED,
            owner = net.badgersmc.em.domain.stall.OwnerRef.unowned(),
            ownerSince = null, winningBid = 0L, rentTerms = RentTerms.formula(1.0),
        )
        val provisioner = mockk<net.badgersmc.em.domain.ports.RegionProvisioner>(relaxed = true)
        val (svc, _, prov) = service(
            regions = listOf(RegionProvider.RegionRef("world", "stall1")),
            existing = listOf(existing),
            provisioner = provisioner,
        )
        val result = svc.import("world", "stall")
        assertEquals(0, result.created)
        assertEquals(1, result.skipped)
        verify { prov.provision("world", "stall1", 20) }
    }
```

Also update the two pre-existing tests: change `listByPrefix("world", "stall_")` expectations to `"stall"`, `svc.import("world", "stall_")` to `"stall"`, and destructure `val (svc, repo) = ...` to `val (svc, repo, _) = ...`. Add `import io.mockk.verify` if not already present (it is).

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew test --tests "net.badgersmc.em.application.ImportStallsServiceTest" -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: FAIL — `ImportStallsService` constructor has no `provisioner`/`stallPriority` params (compile error).

- [ ] **Step 3: Implement provisioning in ImportStallsService**

Replace the body of `ImportStallsService.kt`:

```kotlin
package net.badgersmc.em.application

import net.badgersmc.em.domain.ports.RegionProvider
import net.badgersmc.em.domain.ports.RegionProvisioner
import net.badgersmc.em.domain.stall.*
import net.badgersmc.nexus.annotations.Service

@Service
class ImportStallsService(
    private val regions: RegionProvider,
    private val stalls: StallRepository,
    private val defaultRent: RentTerms,
    private val provisioner: RegionProvisioner,
    private val stallPriority: Int,
) {
    data class Result(val created: Int, val skipped: Int, val provisioned: Int)

    fun import(world: String, prefix: String): Result {
        var created = 0
        var skipped = 0
        var provisioned = 0
        for (ref in regions.listByPrefix(world, prefix)) {
            // Provision flags on EVERY matched region (idempotent) so both
            // new and previously-imported stalls get correct build rights.
            if (provisioner.provision(ref.world, ref.id, stallPriority)) {
                provisioned++
            }
            if (stalls.findByRegion(ref.world, ref.id) != null) {
                skipped++
                continue
            }
            stalls.create(
                Stall(
                    id = StallId(ref.id),
                    regionId = ref.id,
                    world = ref.world,
                    state = StallState.UNOWNED,
                    owner = OwnerRef.unowned(),
                    ownerSince = null,
                    winningBid = 0L,
                    rentTerms = defaultRent,
                )
            )
            created++
        }
        return Result(created, skipped, provisioned)
    }
}
```

Note: `stallPriority: Int` is a non-bean constructor param. Nexus DI must supply it — register it as a bean in `onEnable` (Step 5) the same way `defaultRent` is registered.

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew test --tests "net.badgersmc.em.application.ImportStallsServiceTest" -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: PASS (all ImportStallsServiceTest tests).

- [ ] **Step 5: Register stallPriority bean in onEnable**

In `EnthusiaMarket.kt`, after the `defaultRent` bean registration block (around line 94, after `ctx.registerBean("defaultRent", RentTerms::class, defaultRent)`), add:

```kotlin
        // Provisioning priority for stall regions (REQ Workstream F) — a
        // plain Int bean so ImportStallsService can be DI-constructed.
        ctx.registerBean("stallPriority", Int::class, cfg.market.stallPriority)
```

- [ ] **Step 6: Build to confirm DI wiring compiles**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew compileKotlin -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
cd /d/BadgersMC-Dev/EnthusiaMarket && git add src/main/kotlin/net/badgersmc/em/application/ImportStallsService.kt src/test/kotlin/net/badgersmc/em/application/ImportStallsServiceTest.kt src/main/kotlin/net/badgersmc/em/EnthusiaMarket.kt
git commit -m "feat(import): provision WG flags on every matched region (TDD-281)

Import now stamps priority + build flags on all matched regions
(idempotent) so the 27 unconfigured stalls and any future stalls become
buildable without manual /rg setup. Reports provisioned count.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

### Task F.4: Surface provisioned count in import message

**Files:**
- Modify: `src/main/resources/lang/en_US.yml` (admin.import.result key)
- Modify: `src/main/kotlin/net/badgersmc/em/infrastructure/commands/AdminCommands.kt` (import subcommand, ~line 55-66)

- [ ] **Step 1: Read the current import message key**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && sed -n '13,30p' src/main/resources/lang/en_US.yml`
Expected: shows `admin:` → `import:` → `result:` line with `{created}`/`{skipped}` placeholders.

- [ ] **Step 2: Add {provisioned} to the result message**

In `en_US.yml`, edit the `admin.import.result` value to append the provisioned count. For example if it reads:
`result: "<green>Imported {created} stalls ({skipped} skipped) from {world}:{region_prefix}*"`
change to:
`result: "<green>Imported {created} stalls ({skipped} skipped, {provisioned} regions provisioned) from {world}:{region_prefix}*"`

- [ ] **Step 3: Pass provisioned to the message in AdminCommands**

In `AdminCommands.kt` `import(...)`, add the `provisioned` pair:

```kotlin
    @Subcommand("import")
    @Permission("enthusiamarket.admin.import")
    fun import(@Context sender: CommandSender) {
        val r = service.import(config.market.world, config.market.regionPrefix)
        sender.sendMessage(
            lang.msg(
                "admin.import.result",
                "created" to r.created,
                "skipped" to r.skipped,
                "provisioned" to r.provisioned,
                KEY_WORLD to config.market.world,
                KEY_REGION_PREFIX to config.market.regionPrefix
            )
        )
    }
```

- [ ] **Step 4: Full verify**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew detekt test shadowJar -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Mark tasks done + commit**

Update `docs/tasks.md`: mark TDD-280 and TDD-281 `[x]` with evidence (file paths above). Then:

```bash
cd /d/BadgersMC-Dev/EnthusiaMarket && git add src/main/resources/lang/en_US.yml src/main/kotlin/net/badgersmc/em/infrastructure/commands/AdminCommands.kt docs/tasks.md
git commit -m "feat(import): report provisioned region count; mark TDD-280/281 done

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Workstream E2 — Permissions DSL

Replace the hand-maintained `permissions:` block in `paper-plugin.yml` with the `nexus-permissions-gradle` build-time DSL. This also declares the four missing nodes (`stall.buy/offer/sellback/members`) fixing the drift bug.

**File structure:**
- Modify: `settings.gradle.kts` — plugin resolution mapping for the JitPack-published gradle plugin.
- Modify: `build.gradle.kts` — apply plugin, declare permission tree.
- Modify: `src/main/resources/paper-plugin.yml` — remove the `permissions:` block.

### Task E2.1: Wire the nexus-permissions-gradle plugin

**Files:**
- Modify: `settings.gradle.kts`
- Modify: `build.gradle.kts` (buildscript block, top)

- [ ] **Step 1: Add plugin resolution mapping in settings.gradle.kts**

Replace `settings.gradle.kts` content with:

```kotlin
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven("https://jitpack.io")
    }
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "net.badgersmc.nexus.permissions") {
                useModule("com.github.BadgersMC.Nexus:nexus-permissions-gradle:${requested.version}")
            }
        }
    }
}

rootProject.name = "EnthusiaMarket"
```

- [ ] **Step 2: Add the gradle plugin to buildscript classpath**

In `build.gradle.kts`, the existing `buildscript` block (top of file, lines 1-9) currently declares the detekt plugin. Add the permissions plugin classpath alongside it. Replace the buildscript block with:

```kotlin
buildscript {
    repositories {
        maven("https://plugins.gradle.org/m2/")
        mavenCentral()
        maven("https://jitpack.io")
    }
    dependencies {
        classpath("io.gitlab.arturbosch.detekt:detekt-gradle-plugin:1.23.8")
        classpath("com.github.BadgersMC.Nexus:nexus-permissions-gradle:v2.2.1")
    }
}
```

- [ ] **Step 3: Apply the plugin (after the existing detekt apply line ~18)**

In `build.gradle.kts`, after `apply(plugin = "io.gitlab.arturbosch.detekt")`, add:

```kotlin
apply(plugin = "net.badgersmc.nexus.permissions")
```

- [ ] **Step 4: Build to confirm the plugin resolves**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew help -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: BUILD SUCCESSFUL (plugin downloaded from JitPack, no apply error).

- [ ] **Step 5: Commit**

```bash
cd /d/BadgersMC-Dev/EnthusiaMarket && git add settings.gradle.kts build.gradle.kts
git commit -m "build: wire nexus-permissions-gradle plugin (E2)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

### Task E2.2: Declare the permission tree (incl. drift-fix nodes)

The full tree must reproduce every node currently in `paper-plugin.yml` PLUS the four undeclared nodes used in code (`stall.buy`, `stall.offer`, `stall.sellback`, `stall.members`) and new feature nodes (`stall.setkind`, `stall.entitylimit`, `stall.recount`, `stall.info`, `stall.outline`).

**Files:**
- Modify: `build.gradle.kts` (add permissions extension config block)

- [ ] **Step 1: Add the tree DSL config block**

In `build.gradle.kts`, before the `tasks {` block (around line 133), add the extension configuration. Import `Default` at the top of the file (after the existing imports / before `buildscript` is not valid — place the import at the very top, line 1, Kotlin allows file-level imports before buildscript):

Actually Kotlin DSL requires imports at the very top. Add to the very first lines of `build.gradle.kts` (before `buildscript`):

```kotlin
import net.badgersmc.nexus.permissions.Default
```

Then before `tasks {`, add:

```kotlin
configure<net.badgersmc.nexus.permissions.gradle.NexusPermissionsExtension> {
    tree {
        node("enthusiamarket.*", default = Default.OP, description = "All EnthusiaMarket permissions") {
            child("admin")
            child("player")
        }
        node("enthusiamarket.admin", default = Default.OP, description = "Administrative commands") {
            child("import")
            child("list")
            child("evict")
            child("reload")
            child("setowner")
            child("refund")
        }
        node("enthusiamarket.auction.cancel.force", default = Default.OP, description = "Force-cancel any auction")
        node("enthusiamarket.player", default = Default.TRUE, description = "Player-facing commands") {
            child("stall.rent")
            child("stall.bid")
            child("stall.buy")
            child("stall.offer")
            child("stall.sellback")
            child("stall.members")
            child("stall.manage.own")
            child("stall.manage.guild")
            child("stall.favorite")
            child("stall.info")
            child("stall.outline")
            child("shop.create")
            child("shop.break")
            child("shop.buy")
            child("shop.sell")
            child("auction.start")
            child("auction.bid")
            child("auction.cancel")
            child("auction.list")
            child("bedrock.form")
        }
        // Admin-scoped stall tooling (entity limits, kind, recount).
        node("enthusiamarket.stall.setkind", default = Default.OP, description = "Set a stall's region kind")
        node("enthusiamarket.stall.entitylimit", default = Default.OP, description = "Set per-stall entity-limit override")
        node("enthusiamarket.stall.recount", default = Default.OP, description = "Force entity-count rescan for a stall")
    }
}
```

Note: confirm the exact child-node naming semantics by checking the staged output in Step 2 — the merger may emit children as `enthusiamarket.admin.import` (dotted concatenation) or as literal child names. Adjust child strings to match the production node names in §2 of the spec (`enthusiamarket.admin.import`, `enthusiamarket.stall.buy`, etc.) if the merger does not auto-prefix.

- [ ] **Step 2: Generate and inspect the staged paper-plugin.yml**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew processResources -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain && sed -n '/^permissions:/,$p' build/resources/main/paper-plugin.yml`
Expected: BUILD SUCCESSFUL; the printed `permissions:` block contains `enthusiamarket.stall.buy`, `enthusiamarket.stall.offer`, `enthusiamarket.stall.sellback`, `enthusiamarket.stall.members` with `default: true`, plus all admin nodes. If node names are wrong (missing prefix), fix the DSL child strings and re-run.

- [ ] **Step 3: Commit**

```bash
cd /d/BadgersMC-Dev/EnthusiaMarket && git add build.gradle.kts
git commit -m "build: declare full permission tree via DSL incl. drift-fix nodes (E2)

Adds stall.buy/offer/sellback/members (used in code, previously undeclared
-> players locked out). Generated into paper-plugin.yml at build time.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

### Task E2.3: Strip the hand-maintained permissions block

**Files:**
- Modify: `src/main/resources/paper-plugin.yml` (remove lines 49-82, the `permissions:` block)

- [ ] **Step 1: Remove the permissions block from the source resource**

Delete the entire `permissions:` block (everything from `permissions:` to the end of file, lines ~49-82) from `src/main/resources/paper-plugin.yml`. Leave `name`, `main`, `loader`, `api-version`, `dependencies`, `commands` intact.

- [ ] **Step 2: Regenerate and verify the merger refills permissions**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew clean processResources -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain && sed -n '/^permissions:/,$p' build/resources/main/paper-plugin.yml`
Expected: the staged file STILL contains a full `permissions:` block (regenerated by the merger), including the four drift-fix nodes. Source no longer has it; build output does.

- [ ] **Step 3: Full verify**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew detekt test shadowJar -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: BUILD SUCCESSFUL; verify the shaded jar's `paper-plugin.yml` has permissions (the merger runs before shadowJar).

- [ ] **Step 4: Commit**

```bash
cd /d/BadgersMC-Dev/EnthusiaMarket && git add src/main/resources/paper-plugin.yml
git commit -m "build: remove hand-maintained permissions block; DSL is source of truth (E2)

Permission drift between code and paper-plugin.yml is now structurally
impossible — the tree is declared once in build.gradle.kts and generated.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Workstream B — Entity limits (TDD-220 + TDD-221)

**File structure:**
- Create: `src/main/kotlin/net/badgersmc/em/domain/stall/EntityLimitGroup.kt` — value object + merge.
- Create: `src/main/kotlin/net/badgersmc/em/application/EntityLimitConfig.kt` — loads entitylimits.yml.
- Create: `src/main/kotlin/net/badgersmc/em/application/StallEntityCounter.kt` — hybrid cache + rescan.
- Create: `src/main/kotlin/net/badgersmc/em/infrastructure/listeners/EntityLimitListener.kt` — spawn/place enforcement.
- Create: `src/main/resources/migrations/V013__stall_kind_entity_limits.sql` — new columns.
- Modify: `src/main/kotlin/net/badgersmc/em/domain/stall/Stall.kt` — add kind/extraEntities/extraTotal.
- Modify: `src/main/kotlin/net/badgersmc/em/infrastructure/persistence/StallRepositorySql.kt` — persist new columns.
- Modify: `src/main/kotlin/net/badgersmc/em/infrastructure/commands/AdminCommands.kt` — setkind/entitylimit/recount.
- Tests: matching test files per component.

### Task B.1: EntityLimitGroup domain value object (TDD-220)

**Files:**
- Create: `src/main/kotlin/net/badgersmc/em/domain/stall/EntityLimitGroup.kt`
- Test: `src/test/kotlin/net/badgersmc/em/domain/stall/EntityLimitGroupTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package net.badgersmc.em.domain.stall

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class EntityLimitGroupTest {

    @Test fun `capFor returns per-type cap`() {
        val group = EntityLimitGroup(total = 50, perType = mapOf("villager" to 5, "armor_stand" to 10))
        assertEquals(5, group.capFor("villager"))
        assertEquals(10, group.capFor("armor_stand"))
    }

    @Test fun `capFor returns -1 unlimited for unlisted type`() {
        val group = EntityLimitGroup(total = 50, perType = mapOf("villager" to 5))
        assertEquals(-1, group.capFor("zombie"))
    }

    @Test fun `mergeExtras adds per-stall overrides on top of base caps`() {
        val group = EntityLimitGroup(total = 50, perType = mapOf("villager" to 5))
        val merged = group.mergeExtras(extraTotal = 10, extraPerType = mapOf("villager" to 3, "armor_stand" to 2))
        assertEquals(60, merged.total)
        assertEquals(8, merged.capFor("villager"))
        assertEquals(2, merged.capFor("armor_stand"))
    }

    @Test fun `isOverCap true when count exceeds per-type cap`() {
        val group = EntityLimitGroup(total = 50, perType = mapOf("villager" to 5))
        assertTrue(group.isOverTypeCap("villager", currentCount = 5))
        assertFalse(group.isOverTypeCap("villager", currentCount = 4))
    }

    @Test fun `unlimited per-type cap is never over`() {
        val group = EntityLimitGroup(total = 50, perType = mapOf("villager" to -1))
        assertFalse(group.isOverTypeCap("villager", currentCount = 9999))
    }

    @Test fun `isOverTotal respects total cap and unlimited`() {
        val capped = EntityLimitGroup(total = 50, perType = emptyMap())
        assertTrue(capped.isOverTotal(currentTotal = 50))
        assertFalse(capped.isOverTotal(currentTotal = 49))
        val unlimited = EntityLimitGroup(total = -1, perType = emptyMap())
        assertFalse(unlimited.isOverTotal(currentTotal = 9999))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew test --tests "net.badgersmc.em.domain.stall.EntityLimitGroupTest" -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: FAIL — `EntityLimitGroup` not defined.

- [ ] **Step 3: Implement EntityLimitGroup**

```kotlin
package net.badgersmc.em.domain.stall

/**
 * Per-region-kind entity cap (REQ-220). [total] caps the sum of all capped
 * entities (`_total` in entitylimits.yml); [perType] caps individual
 * Bukkit EntityType names (lower-cased). A cap of -1 means unlimited.
 * Per-stall overrides merge additively via [mergeExtras] (REQ-222).
 */
data class EntityLimitGroup(
    val total: Int,
    val perType: Map<String, Int>,
) {
    /** Per-type cap for [type] (lower-case EntityType name); -1 if unlisted/unlimited. */
    fun capFor(type: String): Int = perType[type] ?: -1

    /** True when [currentCount] of [type] is at or above its cap (unlimited never over). */
    fun isOverTypeCap(type: String, currentCount: Int): Boolean {
        val cap = capFor(type)
        return cap >= 0 && currentCount >= cap
    }

    /** True when [currentTotal] capped entities is at or above [total] (unlimited never over). */
    fun isOverTotal(currentTotal: Int): Boolean = total >= 0 && currentTotal >= total

    /**
     * Merge per-stall extra allowance on top of the base caps. Extras add to
     * both the total and each per-type cap. Unlimited (-1) base caps stay
     * unlimited. See REQ-222.
     */
    fun mergeExtras(extraTotal: Int, extraPerType: Map<String, Int>): EntityLimitGroup {
        val mergedTotal = if (total < 0) -1 else total + extraTotal
        val mergedPerType = perType.toMutableMap()
        for ((type, extra) in extraPerType) {
            val base = perType[type]
            mergedPerType[type] = when {
                base == null -> extra
                base < 0 -> -1
                else -> base + extra
            }
        }
        return EntityLimitGroup(mergedTotal, mergedPerType)
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew test --tests "net.badgersmc.em.domain.stall.EntityLimitGroupTest" -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
cd /d/BadgersMC-Dev/EnthusiaMarket && git add src/main/kotlin/net/badgersmc/em/domain/stall/EntityLimitGroup.kt src/test/kotlin/net/badgersmc/em/domain/stall/EntityLimitGroupTest.kt
git commit -m "feat(domain): EntityLimitGroup value object with merge (TDD-220)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

### Task B.2: Stall kind + extra-entity fields (TDD-220)

**Files:**
- Modify: `src/main/kotlin/net/badgersmc/em/domain/stall/Stall.kt`
- Test: `src/test/kotlin/net/badgersmc/em/domain/stall/StallKindTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package net.badgersmc.em.domain.stall

import kotlin.test.Test
import kotlin.test.assertEquals

class StallKindTest {

    private fun base() = Stall(
        id = StallId("stall1"), regionId = "stall1", world = "world",
        state = StallState.UNOWNED, owner = OwnerRef.unowned(),
        ownerSince = null, winningBid = 0L, rentTerms = RentTerms.formula(1.0),
    )

    @Test fun `new stall defaults to default kind and no extras`() {
        val s = base()
        assertEquals("default", s.kind)
        assertEquals(emptyMap(), s.extraEntities)
        assertEquals(0, s.extraTotal)
    }

    @Test fun `kind and extras are settable via copy`() {
        val s = base().copy(kind = "shop", extraEntities = mapOf("villager" to 3), extraTotal = 10)
        assertEquals("shop", s.kind)
        assertEquals(3, s.extraEntities["villager"])
        assertEquals(10, s.extraTotal)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew test --tests "net.badgersmc.em.domain.stall.StallKindTest" -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: FAIL — `kind`/`extraEntities`/`extraTotal` unresolved.

- [ ] **Step 3: Add fields to Stall**

In `Stall.kt`, add three fields to the data class constructor after `nextRentAt` (before the closing `)` of the constructor, line ~35):

```kotlin
    /**
     * Region kind (REQ-220), keyed into entitylimits.yml groups. Resolved
     * at import/claim and stored (migration V013). Defaults to "default".
     */
    val kind: String = "default",
    /**
     * Per-stall additional entity allowance on top of the kind's group
     * caps (REQ-222). Key is lower-case EntityType name.
     */
    val extraEntities: Map<String, Int> = emptyMap(),
    /** Per-stall additional total-entity allowance (REQ-222). */
    val extraTotal: Int = 0,
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew test --tests "net.badgersmc.em.domain.stall.StallKindTest" -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
cd /d/BadgersMC-Dev/EnthusiaMarket && git add src/main/kotlin/net/badgersmc/em/domain/stall/Stall.kt src/test/kotlin/net/badgersmc/em/domain/stall/StallKindTest.kt
git commit -m "feat(domain): add Stall.kind/extraEntities/extraTotal (TDD-220)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

### Task B.3: Migration V013 + persistence of new columns

**Files:**
- Create: `src/main/resources/migrations/V013__stall_kind_entity_limits.sql`
- Modify: `src/main/kotlin/net/badgersmc/em/infrastructure/persistence/StallRepositorySql.kt`
- Test: `src/test/kotlin/net/badgersmc/em/infrastructure/persistence/StallRepositorySqlKindTest.kt`

- [ ] **Step 1: Write the migration**

```sql
-- REQ-220/222 — per-stall region kind + entity-limit overrides. kind keys
-- into entitylimits.yml groups; extra_entities (JSON map of type->extra) and
-- extra_total grant individual stalls additional allowance. Existing rows
-- backfill to the 'default' kind with no extras.
ALTER TABLE stalls ADD COLUMN kind TEXT NOT NULL DEFAULT 'default';
ALTER TABLE stalls ADD COLUMN extra_entities TEXT NOT NULL DEFAULT '';
ALTER TABLE stalls ADD COLUMN extra_total INTEGER NOT NULL DEFAULT 0;
```

- [ ] **Step 2: Write the failing persistence round-trip test**

```kotlin
package net.badgersmc.em.infrastructure.persistence

import net.badgersmc.em.domain.stall.*
import net.badgersmc.nexus.persistence.DatabaseFactory
import net.badgersmc.nexus.persistence.DatabaseSpec
import net.badgersmc.nexus.persistence.MigrationRunner
import java.io.File
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.AfterTest

class StallRepositorySqlKindTest {

    private val dbFile = File.createTempFile("em-kind-test", ".db")
    private val ds: DataSource = DatabaseFactory.open(DatabaseSpec.Sqlite(dbFile)).also {
        MigrationRunner(it, resourcePrefix = "migrations", classLoader = javaClass.classLoader).runAll()
    }

    @AfterTest fun cleanup() { dbFile.delete() }

    @Test fun `kind and extras survive a create-read round trip`() {
        val repo = StallRepositorySql(ds)
        val stall = Stall(
            id = StallId("stall1"), regionId = "stall1", world = "world",
            state = StallState.UNOWNED, owner = OwnerRef.unowned(),
            ownerSince = null, winningBid = 0L, rentTerms = RentTerms.formula(1.0),
            kind = "shop", extraEntities = mapOf("villager" to 3, "armor_stand" to 2),
            extraTotal = 10,
        )
        repo.create(stall)
        val loaded = repo.findById(StallId("stall1"))!!
        assertEquals("shop", loaded.kind)
        assertEquals(3, loaded.extraEntities["villager"])
        assertEquals(2, loaded.extraEntities["armor_stand"])
        assertEquals(10, loaded.extraTotal)
    }

    @Test fun `defaults apply for a minimal stall`() {
        val repo = StallRepositorySql(ds)
        repo.create(Stall(
            id = StallId("stall2"), regionId = "stall2", world = "world",
            state = StallState.UNOWNED, owner = OwnerRef.unowned(),
            ownerSince = null, winningBid = 0L, rentTerms = RentTerms.formula(1.0),
        ))
        val loaded = repo.findById(StallId("stall2"))!!
        assertEquals("default", loaded.kind)
        assertEquals(emptyMap(), loaded.extraEntities)
        assertEquals(0, loaded.extraTotal)
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew test --tests "net.badgersmc.em.infrastructure.persistence.StallRepositorySqlKindTest" -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: FAIL — INSERT/SELECT don't yet handle the new columns (the loaded kind is missing / SQL error on unknown column in mapRow).

- [ ] **Step 4: Update StallRepositorySql for the new columns**

In `StallRepositorySql.kt`:

(a) `create` INSERT column list + placeholders — add `kind, extra_entities, extra_total` and three `?`:

```kotlin
            conn.prepareStatement(
                """INSERT INTO stalls
                   (id, region_id, world, state, owner_type, owner_id, owner_since,
                    winning_bid, rent_mode, rent_pct, rent_flat, members, max_members, next_rent_at,
                    kind, extra_entities, extra_total)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"""
            ).use { ps ->
```

(b) `bind` — append after the `next_rent_at` bind (param 14), params 15-17:

```kotlin
        ps.setString(15, stall.kind)
        ps.setString(16, encodeExtras(stall.extraEntities))
        ps.setInt(17, stall.extraTotal)
```

(c) `save` UPDATE — add the three columns before `WHERE id = ?` and renumber the `WHERE` param. Change the SQL and binds:

```kotlin
            conn.prepareStatement(
                """UPDATE stalls SET
                     region_id = ?, world = ?, state = ?, owner_type = ?, owner_id = ?,
                     owner_since = ?, winning_bid = ?, rent_mode = ?, rent_pct = ?, rent_flat = ?,
                     members = ?, max_members = ?, next_rent_at = ?,
                     kind = ?, extra_entities = ?, extra_total = ?
                   WHERE id = ?"""
            ).use { ps ->
                ps.setString(1, stall.regionId)
                ps.setString(2, stall.world)
                ps.setString(3, stall.state.name)
                ps.setString(4, stall.owner.type.name)
                ps.setString(5, stall.owner.id)
                if (stall.ownerSince != null) ps.setLong(6, stall.ownerSince.toEpochMilli())
                else ps.setNull(6, java.sql.Types.INTEGER)
                ps.setLong(7, stall.winningBid)
                ps.setString(8, stall.rentTerms.mode.name)
                ps.setDouble(9, stall.rentTerms.pct)
                ps.setLong(10, stall.rentTerms.flatAmount)
                ps.setString(11, encodeMembers(stall.members))
                ps.setInt(12, stall.maxMembers)
                if (stall.nextRentAt != null) ps.setLong(13, stall.nextRentAt.toEpochMilli())
                else ps.setNull(13, java.sql.Types.INTEGER)
                ps.setString(14, stall.kind)
                ps.setString(15, encodeExtras(stall.extraEntities))
                ps.setInt(16, stall.extraTotal)
                ps.setString(17, stall.id.value)
                ps.executeUpdate()
            }
```

(d) `mapRow` — add kind/extras to the returned Stall (before the closing `)`):

```kotlin
            kind = rs.getString("kind") ?: "default",
            extraEntities = decodeExtras(rs.getString("extra_entities")),
            extraTotal = rs.getInt("extra_total"),
```

(e) Add encode/decode helpers next to `encodeMembers`/`decodeMembers`:

```kotlin
    private fun encodeExtras(extras: Map<String, Int>): String =
        extras.entries.joinToString(",") { "${it.key}=${it.value}" }

    private fun decodeExtras(raw: String?): Map<String, Int> {
        if (raw.isNullOrBlank()) return emptyMap()
        return raw.split(',')
            .asSequence()
            .filter { it.isNotBlank() && it.contains('=') }
            .associate { entry ->
                val (k, v) = entry.split('=', limit = 2)
                k.trim() to (v.trim().toIntOrNull() ?: 0)
            }
    }
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew test --tests "net.badgersmc.em.infrastructure.persistence.StallRepositorySqlKindTest" -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: PASS

- [ ] **Step 6: Run the full suite to confirm no regression in other persistence tests**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew test --tests "net.badgersmc.em.infrastructure.persistence.*" -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: PASS (existing StallRepositorySql tests still green — the new columns have defaults).

- [ ] **Step 7: Commit**

```bash
cd /d/BadgersMC-Dev/EnthusiaMarket && git add src/main/resources/migrations/V013__stall_kind_entity_limits.sql src/main/kotlin/net/badgersmc/em/infrastructure/persistence/StallRepositorySql.kt src/test/kotlin/net/badgersmc/em/infrastructure/persistence/StallRepositorySqlKindTest.kt
git commit -m "feat(persistence): V013 stall kind + entity-limit columns (TDD-220)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

### Task B.4: EntityLimitConfig loader

Loads `entitylimits.yml` (already shipped) into `Map<String, EntityLimitGroup>`. The file is plain YAML keyed by kind; uses SnakeYAML via Bukkit's `YamlConfiguration` (available on the Bukkit API classpath).

**Files:**
- Create: `src/main/kotlin/net/badgersmc/em/application/EntityLimitConfig.kt`
- Test: `src/test/kotlin/net/badgersmc/em/application/EntityLimitConfigTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package net.badgersmc.em.application

import net.badgersmc.em.domain.stall.EntityLimitGroup
import kotlin.test.Test
import kotlin.test.assertEquals

class EntityLimitConfigTest {

    private val yaml = """
        default:
          _total: 50
          villager: 5
          armor_stand: 10
        shop:
          _total: 80
          villager: 10
    """.trimIndent()

    @Test fun `parses groups keyed by kind`() {
        val groups = EntityLimitConfig.parse(yaml)
        assertEquals(50, groups.getValue("default").total)
        assertEquals(5, groups.getValue("default").capFor("villager"))
        assertEquals(10, groups.getValue("default").capFor("armor_stand"))
        assertEquals(80, groups.getValue("shop").total)
        assertEquals(10, groups.getValue("shop").capFor("villager"))
    }

    @Test fun `groupFor returns default group when kind missing`() {
        val groups = EntityLimitConfig.parse(yaml)
        val resolved = EntityLimitConfig.groupFor(groups, "nonexistent")
        assertEquals(groups.getValue("default"), resolved)
    }

    @Test fun `groupFor returns empty unlimited group when no default present`() {
        val groups = EntityLimitConfig.parse("shop:\n  _total: 80")
        val resolved = EntityLimitConfig.groupFor(groups, "unknown")
        assertEquals(EntityLimitGroup(total = -1, perType = emptyMap()), resolved)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew test --tests "net.badgersmc.em.application.EntityLimitConfigTest" -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: FAIL — `EntityLimitConfig` not defined.

- [ ] **Step 3: Implement EntityLimitConfig (parse via Bukkit YamlConfiguration)**

```kotlin
package net.badgersmc.em.application

import net.badgersmc.em.domain.stall.EntityLimitGroup
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

/**
 * Loads entitylimits.yml (REQ-220) into a map of region-kind name ->
 * [EntityLimitGroup]. The `_total` key maps to the group total; every
 * other key is a lower-case EntityType cap. -1 means unlimited.
 */
object EntityLimitConfig {

    private const val TOTAL_KEY = "_total"

    /** Parse a YAML string (used by tests and the file loader). */
    fun parse(yaml: String): Map<String, EntityLimitGroup> {
        val config = YamlConfiguration()
        config.loadFromString(yaml)
        val out = LinkedHashMap<String, EntityLimitGroup>()
        for (kind in config.getKeys(false)) {
            val section = config.getConfigurationSection(kind) ?: continue
            var total = -1
            val perType = LinkedHashMap<String, Int>()
            for (key in section.getKeys(false)) {
                val value = section.getInt(key)
                if (key == TOTAL_KEY) total = value else perType[key.lowercase()] = value
            }
            out[kind] = EntityLimitGroup(total, perType)
        }
        return out
    }

    /** Load from a file; returns empty map when the file is absent. */
    fun load(file: File): Map<String, EntityLimitGroup> =
        if (file.exists()) parse(file.readText()) else emptyMap()

    /**
     * Resolve the group for [kind], falling back to the `default` group, then
     * to an unlimited empty group when no default is configured.
     */
    fun groupFor(groups: Map<String, EntityLimitGroup>, kind: String): EntityLimitGroup =
        groups[kind] ?: groups["default"] ?: EntityLimitGroup(total = -1, perType = emptyMap())
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew test --tests "net.badgersmc.em.application.EntityLimitConfigTest" -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
cd /d/BadgersMC-Dev/EnthusiaMarket && git add src/main/kotlin/net/badgersmc/em/application/EntityLimitConfig.kt src/test/kotlin/net/badgersmc/em/application/EntityLimitConfigTest.kt
git commit -m "feat(application): EntityLimitConfig loader for entitylimits.yml (TDD-220)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

### Task B.5: StallEntityCounter (hybrid cache + boundary rescan)

The counter holds an in-memory per-stall, per-type count. The authoritative rescan is delegated to an injectable lambda so the listener can supply a real `world.getNearbyEntities` scan while tests supply a stub. This keeps the counter unit-testable without MockBukkit.

**Files:**
- Create: `src/main/kotlin/net/badgersmc/em/application/StallEntityCounter.kt`
- Test: `src/test/kotlin/net/badgersmc/em/application/StallEntityCounterTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package net.badgersmc.em.application

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class StallEntityCounterTest {

    @Test fun `increment and read cached count`() {
        val counter = StallEntityCounter()
        counter.increment("stall1", "villager")
        counter.increment("stall1", "villager")
        assertEquals(2, counter.cachedCount("stall1", "villager"))
    }

    @Test fun `decrement never goes below zero`() {
        val counter = StallEntityCounter()
        counter.increment("stall1", "villager")
        counter.decrement("stall1", "villager")
        counter.decrement("stall1", "villager")
        assertEquals(0, counter.cachedCount("stall1", "villager"))
    }

    @Test fun `cachedTotal sums all types in a stall`() {
        val counter = StallEntityCounter()
        counter.increment("stall1", "villager")
        counter.increment("stall1", "armor_stand")
        assertEquals(2, counter.cachedTotal("stall1"))
    }

    @Test fun `recount replaces cache with authoritative scan`() {
        val counter = StallEntityCounter()
        counter.increment("stall1", "villager") // stale cache says 1
        counter.recount("stall1", mapOf("villager" to 7, "armor_stand" to 2))
        assertEquals(7, counter.cachedCount("stall1", "villager"))
        assertEquals(2, counter.cachedCount("stall1", "armor_stand"))
        assertEquals(9, counter.cachedTotal("stall1"))
    }

    @Test fun `wouldExceedTypeCap uses authoritative recount at the boundary`() {
        val counter = StallEntityCounter()
        // Cache says 5 (at cap), but authoritative scan says only 3 -> allow.
        repeat(5) { counter.increment("stall1", "villager") }
        val authoritative = { _: String -> mapOf("villager" to 3) }
        val exceeded = counter.wouldExceedTypeCap("stall1", "villager", cap = 5, rescan = authoritative)
        assertFalse(exceeded)
        // After rescan, cache reflects the authoritative count.
        assertEquals(3, counter.cachedCount("stall1", "villager"))
    }

    @Test fun `wouldExceedTypeCap true when authoritative count still at cap`() {
        val counter = StallEntityCounter()
        repeat(5) { counter.increment("stall1", "villager") }
        val authoritative = { _: String -> mapOf("villager" to 5) }
        assertTrue(counter.wouldExceedTypeCap("stall1", "villager", cap = 5, rescan = authoritative))
    }

    @Test fun `unlimited cap never exceeds`() {
        val counter = StallEntityCounter()
        repeat(100) { counter.increment("stall1", "villager") }
        val exceeded = counter.wouldExceedTypeCap("stall1", "villager", cap = -1, rescan = { emptyMap() })
        assertFalse(exceeded)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew test --tests "net.badgersmc.em.application.StallEntityCounterTest" -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: FAIL — `StallEntityCounter` not defined.

- [ ] **Step 3: Implement StallEntityCounter**

```kotlin
package net.badgersmc.em.application

import net.badgersmc.nexus.annotations.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * Hybrid per-stall entity counter (spec §3 Workstream B). Fast path is an
 * in-memory cache mutated by spawn/death events. At the cap boundary the
 * caller supplies an authoritative [rescan] (a live world scan) so a drifted
 * cache never produces a false cancel. Cache keys are stall id strings;
 * type keys are lower-case EntityType names.
 */
@Component
class StallEntityCounter {

    private val cache = ConcurrentHashMap<String, ConcurrentHashMap<String, Int>>()

    fun increment(stallId: String, type: String) {
        val byType = cache.computeIfAbsent(stallId) { ConcurrentHashMap() }
        byType.merge(type, 1, Int::plus)
    }

    fun decrement(stallId: String, type: String) {
        val byType = cache[stallId] ?: return
        byType.compute(type) { _, current -> ((current ?: 0) - 1).coerceAtLeast(0) }
    }

    fun cachedCount(stallId: String, type: String): Int = cache[stallId]?.get(type) ?: 0

    fun cachedTotal(stallId: String): Int = cache[stallId]?.values?.sum() ?: 0

    /** Replace the cached counts for [stallId] with an authoritative scan. */
    fun recount(stallId: String, counts: Map<String, Int>) {
        cache[stallId] = ConcurrentHashMap(counts)
    }

    /**
     * True when adding one more [type] would exceed [cap]. Unlimited (cap<0)
     * never exceeds. At/over the cap by cache, run [rescan] for the stall to
     * get authoritative counts, refresh the cache, and re-check.
     */
    fun wouldExceedTypeCap(
        stallId: String,
        type: String,
        cap: Int,
        rescan: (String) -> Map<String, Int>,
    ): Boolean {
        if (cap < 0) return false
        if (cachedCount(stallId, type) < cap) return false
        // Boundary: confirm with an authoritative scan before refusing.
        val authoritative = rescan(stallId)
        recount(stallId, authoritative)
        return cachedCount(stallId, type) >= cap
    }

    /**
     * True when adding one more entity would exceed the total [cap].
     * Unlimited (cap<0) never exceeds. Mirrors [wouldExceedTypeCap] using the
     * stall total.
     */
    fun wouldExceedTotal(
        stallId: String,
        cap: Int,
        rescan: (String) -> Map<String, Int>,
    ): Boolean {
        if (cap < 0) return false
        if (cachedTotal(stallId) < cap) return false
        val authoritative = rescan(stallId)
        recount(stallId, authoritative)
        return cachedTotal(stallId) >= cap
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew test --tests "net.badgersmc.em.application.StallEntityCounterTest" -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
cd /d/BadgersMC-Dev/EnthusiaMarket && git add src/main/kotlin/net/badgersmc/em/application/StallEntityCounter.kt src/test/kotlin/net/badgersmc/em/application/StallEntityCounterTest.kt
git commit -m "feat(application): hybrid StallEntityCounter (cache + boundary rescan) (TDD-221)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

### Task B.6: EntityLimitListener (spawn/place enforcement)

The listener resolves the enclosing stall via `RegionProvider`, loads the Stall + its kind group, and cancels over-cap spawns. It filters `CreatureSpawnEvent` to player-attributable reasons. The enclosing-stall lookup needs a region-at-location query — add `regionAt(world, x, y, z): String?` to `RegionProvider`.

**Files:**
- Modify: `src/main/kotlin/net/badgersmc/em/domain/ports/RegionProvider.kt` — add `regionAt`.
- Modify: `src/main/kotlin/net/badgersmc/em/infrastructure/worldguard/WorldGuardRegionProvider.kt` — implement `regionAt` + `entityCountsIn`.
- Create: `src/main/kotlin/net/badgersmc/em/infrastructure/listeners/EntityLimitListener.kt`
- Test: `src/test/kotlin/net/badgersmc/em/infrastructure/listeners/EntityLimitListenerTest.kt`

- [ ] **Step 1: Add regionAt to RegionProvider port**

In `RegionProvider.kt`, add inside the interface:

```kotlin
    /**
     * Highest-priority region id containing the block at ([x],[y],[z]) in
     * [world] whose id is a stall, or null when no region covers the point.
     * Implementations may filter to the configured stall prefix.
     */
    fun regionAt(world: String, x: Int, y: Int, z: Int): String?
```

- [ ] **Step 2: Implement regionAt in WorldGuardRegionProvider**

Add to `WorldGuardRegionProvider.kt` (uses `ApplicableRegionSet`):

```kotlin
    override fun regionAt(world: String, x: Int, y: Int, z: Int): String? {
        val bukkitWorld = Bukkit.getWorld(world) ?: return null
        val container = WorldGuard.getInstance().platform.regionContainer
        val regionManager = container.get(
            com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(bukkitWorld)
        ) ?: return null
        val applicable = regionManager.getApplicableRegions(
            com.sk89q.worldedit.math.BlockVector3.at(x, y, z)
        )
        // Highest priority region wins; return its id.
        return applicable.regions
            .maxByOrNull { it.priority }
            ?.id
    }
```

- [ ] **Step 3: Write the failing listener test (MockBukkit-free, mocks the port)**

The listener's pure decision logic is extracted into a testable method `decide(stallId, type, group, counter, rescan)` returning a Boolean (cancel?). Test that logic directly:

```kotlin
package net.badgersmc.em.infrastructure.listeners

import net.badgersmc.em.application.StallEntityCounter
import net.badgersmc.em.domain.stall.EntityLimitGroup
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class EntityLimitListenerTest {

    private val group = EntityLimitGroup(total = 50, perType = mapOf("villager" to 5))

    @Test fun `cancels when type at cap and authoritative confirms`() {
        val counter = StallEntityCounter()
        repeat(5) { counter.increment("stall1", "villager") }
        val cancel = EntityLimitListener.decide(
            "stall1", "villager", group, counter, rescan = { mapOf("villager" to 5) }
        )
        assertTrue(cancel)
    }

    @Test fun `allows when under type cap`() {
        val counter = StallEntityCounter()
        counter.increment("stall1", "villager")
        val cancel = EntityLimitListener.decide(
            "stall1", "villager", group, counter, rescan = { mapOf("villager" to 1) }
        )
        assertFalse(cancel)
    }

    @Test fun `cancels when total at cap even if type under`() {
        val smallTotal = EntityLimitGroup(total = 2, perType = mapOf("villager" to 99))
        val counter = StallEntityCounter()
        counter.increment("stall1", "villager")
        counter.increment("stall1", "armor_stand")
        val cancel = EntityLimitListener.decide(
            "stall1", "villager", smallTotal, counter, rescan = { mapOf("villager" to 1, "armor_stand" to 1) }
        )
        assertTrue(cancel)
    }

    @Test fun `allows on increment when accepted`() {
        val counter = StallEntityCounter()
        val cancel = EntityLimitListener.decide(
            "stall1", "villager", group, counter, rescan = { emptyMap() }
        )
        assertFalse(cancel)
        // Accepted spawn bumps the cache.
        assertTrue(counter.cachedCount("stall1", "villager") >= 1)
    }
}
```

- [ ] **Step 4: Run the test to verify it fails**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew test --tests "net.badgersmc.em.infrastructure.listeners.EntityLimitListenerTest" -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: FAIL — `EntityLimitListener` not defined.

- [ ] **Step 5: Implement EntityLimitListener**

```kotlin
package net.badgersmc.em.infrastructure.listeners

import net.badgersmc.em.application.EntityLimitConfig
import net.badgersmc.em.application.StallEntityCounter
import net.badgersmc.em.config.EnthusiaMarketConfig
import net.badgersmc.em.domain.stall.EntityLimitGroup
import net.badgersmc.em.domain.stall.StallId
import net.badgersmc.em.domain.stall.StallRepository
import net.badgersmc.em.domain.ports.RegionProvider
import net.badgersmc.nexus.annotations.Component
import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.EntityPlaceEvent
import org.bukkit.event.hanging.HangingPlaceEvent

/**
 * Enforces per-stall entity caps (REQ-221). Player-attributable creature
 * spawns and all entity/hanging placements are checked against the stall's
 * kind group (merged with per-stall extras). Natural spawns are ignored.
 * See spec §3 Workstream B.
 */
@net.badgersmc.nexus.paper.listeners.Listener
@Component
class EntityLimitListener(
    private val regions: RegionProvider,
    private val stalls: StallRepository,
    private val config: EnthusiaMarketConfig,
    private val counter: StallEntityCounter,
    private val plugin: org.bukkit.plugin.Plugin,
) : Listener {

    // Loaded once on construction; reload via /em reload re-creates beans.
    private val groups: Map<String, EntityLimitGroup> =
        EntityLimitConfig.load(java.io.File(plugin.dataFolder, "entitylimits.yml"))

    companion object {
        /** Spawn reasons attributable to players (REQ-221) — natural spawns excluded. */
        private val PLAYER_REASONS = setOf(
            CreatureSpawnEvent.SpawnReason.BREEDING,
            CreatureSpawnEvent.SpawnReason.SPAWNER_EGG,
            CreatureSpawnEvent.SpawnReason.EGG,
            CreatureSpawnEvent.SpawnReason.DISPENSE_EGG,
            CreatureSpawnEvent.SpawnReason.BUILD_IRONGOLEM,
            CreatureSpawnEvent.SpawnReason.BUILD_SNOWMAN,
            CreatureSpawnEvent.SpawnReason.BUILD_WITHER,
        )

        /**
         * Pure decision: should this spawn/place be cancelled? Increments the
         * counter on accept. Exposed for unit testing without Bukkit.
         */
        fun decide(
            stallId: String,
            type: String,
            group: EntityLimitGroup,
            counter: StallEntityCounter,
            rescan: (String) -> Map<String, Int>,
        ): Boolean {
            if (counter.wouldExceedTypeCap(stallId, type, group.capFor(type), rescan)) return true
            if (counter.wouldExceedTotal(stallId, group.total, rescan)) return true
            counter.increment(stallId, type)
            return false
        }
    }

    @EventHandler
    fun onCreatureSpawn(event: CreatureSpawnEvent) {
        if (event.spawnReason !in PLAYER_REASONS) return
        if (checkAndMaybeCancel(event.location, event.entityType.name)) event.isCancelled = true
    }

    @EventHandler
    fun onEntityPlace(event: EntityPlaceEvent) {
        if (checkAndMaybeCancel(event.entity.location, event.entity.type.name)) event.isCancelled = true
    }

    @EventHandler
    fun onHangingPlace(event: HangingPlaceEvent) {
        if (checkAndMaybeCancel(event.entity.location, event.entity.type.name)) event.isCancelled = true
    }

    @EventHandler
    fun onEntityDeath(event: EntityDeathEvent) {
        val stallId = resolveStall(event.entity.location) ?: return
        counter.decrement(stallId, event.entityType.name.lowercase())
    }

    private fun checkAndMaybeCancel(loc: Location, entityTypeName: String): Boolean {
        val stallId = resolveStall(loc) ?: return false
        val stall = stalls.findById(StallId(stallId)) ?: return false
        val baseGroup = EntityLimitConfig.groupFor(groups, stall.kind)
        val group = baseGroup.mergeExtras(stall.extraTotal, stall.extraEntities)
        val type = entityTypeName.lowercase()
        return decide(stallId, type, group, counter, ::scanCounts)
    }

    private fun resolveStall(loc: Location): String? {
        val world = loc.world?.name ?: return null
        val id = regions.regionAt(world, loc.blockX, loc.blockY, loc.blockZ) ?: return null
        return if (id.startsWith(config.market.regionPrefix)) id else null
    }

    /** Authoritative live scan of capped entity counts within the stall region. */
    private fun scanCounts(stallId: String): Map<String, Int> {
        val stall = stalls.findById(StallId(stallId)) ?: return emptyMap()
        val world = org.bukkit.Bukkit.getWorld(stall.world) ?: return emptyMap()
        val counts = HashMap<String, Int>()
        // Scan entities whose location resolves back to this stall region.
        for (entity: Entity in world.entities) {
            if (resolveStall(entity.location) == stallId) {
                val t = entity.type.name.lowercase()
                counts[t] = (counts[t] ?: 0) + 1
            }
        }
        return counts
    }
}
```

- [ ] **Step 6: Run the test to verify it passes**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew test --tests "net.badgersmc.em.infrastructure.listeners.EntityLimitListenerTest" -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: PASS

- [ ] **Step 7: Build to confirm WG regionAt API resolves**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew compileKotlin -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: BUILD SUCCESSFUL. If `getApplicableRegions`/`BlockVector3` signatures differ, consult WG 7.0.9 — `RegionManager.getApplicableRegions(BlockVector3)` returns `ApplicableRegionSet` which is iterable over `ProtectedRegion`.

- [ ] **Step 8: Update other RegionProvider mocks if compile breaks tests**

Adding `regionAt` to the interface may break existing mocks that don't relax it. Run:
`cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew compileTestKotlin -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: BUILD SUCCESSFUL (mockk relaxed mocks auto-stub new methods). If any non-relaxed `mockk<RegionProvider>()` fails, add `every { it.regionAt(any(), any(), any(), any()) } returns null`.

- [ ] **Step 9: Commit**

```bash
cd /d/BadgersMC-Dev/EnthusiaMarket && git add src/main/kotlin/net/badgersmc/em/domain/ports/RegionProvider.kt src/main/kotlin/net/badgersmc/em/infrastructure/worldguard/WorldGuardRegionProvider.kt src/main/kotlin/net/badgersmc/em/infrastructure/listeners/EntityLimitListener.kt src/test/kotlin/net/badgersmc/em/infrastructure/listeners/EntityLimitListenerTest.kt
git commit -m "feat(listeners): EntityLimitListener enforces per-stall caps (TDD-221)

Player-attributable spawns + entity/hanging placements checked against the
stall kind group merged with per-stall extras. Hybrid counter with
boundary rescan. Natural spawns ignored.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

### Task B.7: Admin commands setkind / entitylimit / recount

**Files:**
- Modify: `src/main/kotlin/net/badgersmc/em/infrastructure/commands/AdminCommands.kt`
- Modify: `src/main/resources/lang/en_US.yml` (new stall.* admin messages)
- Test: `src/test/kotlin/net/badgersmc/em/infrastructure/commands/AdminCommandsEntityLimitTest.kt`

- [ ] **Step 1: Write the failing test for setkind persistence**

```kotlin
package net.badgersmc.em.infrastructure.commands

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import net.badgersmc.em.domain.stall.*
import kotlin.test.Test
import kotlin.test.assertEquals

class AdminCommandsEntityLimitTest {

    private fun stall(kind: String = "default") = Stall(
        id = StallId("stall1"), regionId = "stall1", world = "world",
        state = StallState.UNOWNED, owner = OwnerRef.unowned(),
        ownerSince = null, winningBid = 0L, rentTerms = RentTerms.formula(1.0),
        kind = kind,
    )

    @Test fun `setkind persists the new kind`() {
        val repo = mockk<StallRepository>(relaxed = true)
        every { repo.findById(StallId("stall1")) } returns stall()
        val saved = slot<Stall>()
        every { repo.save(capture(saved)) } returns Unit

        // applySetKind is the extracted pure helper on AdminCommands' logic.
        val updated = AdminCommands.applySetKind(repo, "stall1", "shop")
        assertEquals(true, updated)
        assertEquals("shop", saved.captured.kind)
    }

    @Test fun `setkind returns false for missing stall`() {
        val repo = mockk<StallRepository>(relaxed = true)
        every { repo.findById(StallId("nope")) } returns null
        assertEquals(false, AdminCommands.applySetKind(repo, "nope", "shop"))
    }

    @Test fun `entitylimit set persists per-stall override`() {
        val repo = mockk<StallRepository>(relaxed = true)
        every { repo.findById(StallId("stall1")) } returns stall()
        val saved = slot<Stall>()
        every { repo.save(capture(saved)) } returns Unit

        val ok = AdminCommands.applyEntityLimit(repo, "stall1", "villager", 3)
        assertEquals(true, ok)
        assertEquals(3, saved.captured.extraEntities["villager"])
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew test --tests "net.badgersmc.em.infrastructure.commands.AdminCommandsEntityLimitTest" -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: FAIL — `applySetKind`/`applyEntityLimit` not defined.

- [ ] **Step 3: Add the companion helpers + subcommands to AdminCommands**

In `AdminCommands.kt`, add a `companion object` with the pure helpers and inject `StallEntityCounter` into the constructor (add `private val entityCounter: net.badgersmc.em.application.StallEntityCounter,` to the constructor params). Add the companion:

```kotlin
    companion object {
        const val KEY_WORLD = "world"
        const val KEY_REGION_PREFIX = "region_prefix"

        /** Set a stall's region kind. Returns false when the stall is missing. */
        fun applySetKind(repo: StallRepository, stallId: String, kind: String): Boolean {
            val stall = repo.findById(StallId(stallId)) ?: return false
            repo.save(stall.copy(kind = kind))
            return true
        }

        /** Set a per-stall per-type entity-limit override. Returns false when missing. */
        fun applyEntityLimit(repo: StallRepository, stallId: String, type: String, extra: Int): Boolean {
            val stall = repo.findById(StallId(stallId)) ?: return false
            val merged = stall.extraEntities.toMutableMap()
            merged[type.lowercase()] = extra
            repo.save(stall.copy(extraEntities = merged))
            return true
        }
    }
```

Note: if `KEY_WORLD`/`KEY_REGION_PREFIX` already exist as top-level or companion consts elsewhere in the file, do not duplicate — reuse the existing declaration and only add the two functions.

Then add the three subcommands (methods on the class):

```kotlin
    @Subcommand("stall setkind")
    @Permission("enthusiamarket.stall.setkind")
    fun stallSetKind(@Context sender: CommandSender, @Arg stallId: String, @Arg kind: String) {
        val ok = applySetKind(stalls, stallId, kind)
        sender.sendMessage(
            if (ok) lang.msg("stall.setkind.ok", "stall" to stallId, "kind" to kind)
            else lang.msg("stall.setkind.missing", "stall" to stallId)
        )
    }

    @Subcommand("stall entitylimit set")
    @Permission("enthusiamarket.stall.entitylimit")
    fun stallEntityLimit(@Context sender: CommandSender, @Arg stallId: String, @Arg type: String, @Arg extra: Int) {
        val ok = applyEntityLimit(stalls, stallId, type, extra)
        sender.sendMessage(
            if (ok) lang.msg("stall.entitylimit.ok", "stall" to stallId, "type" to type, "extra" to extra)
            else lang.msg("stall.entitylimit.missing", "stall" to stallId)
        )
    }

    @Subcommand("stall recount")
    @Permission("enthusiamarket.stall.recount")
    fun stallRecount(@Context sender: CommandSender, @Arg stallId: String) {
        val stall = stalls.findById(StallId(stallId))
        if (stall == null) {
            sender.sendMessage(lang.msg("stall.recount.missing", "stall" to stallId))
            return
        }
        val world = org.bukkit.Bukkit.getWorld(stall.world)
        val counts = HashMap<String, Int>()
        if (world != null) {
            for (entity in world.entities) {
                val id = regionMembers.let { _ ->
                    // resolve via region provider injected as `regionProvider`
                    regionProvider.regionAt(stall.world, entity.location.blockX, entity.location.blockY, entity.location.blockZ)
                }
                if (id == stallId) {
                    val t = entity.type.name.lowercase()
                    counts[t] = (counts[t] ?: 0) + 1
                }
            }
        }
        entityCounter.recount(stallId, counts)
        sender.sendMessage(lang.msg("stall.recount.ok", "stall" to stallId, "total" to counts.values.sum()))
    }
```

This requires `RegionProvider` injected too — add `private val regionProvider: net.badgersmc.em.domain.ports.RegionProvider,` to the constructor.

- [ ] **Step 4: Add the lang keys**

In `en_US.yml`, under the `stall:` section, add:

```yaml
  setkind:
    ok: "<green>Stall {stall} kind set to {kind}"
    missing: "<red>No stall named {stall}"
  entitylimit:
    ok: "<green>Stall {stall}: {type} extra allowance set to {extra}"
    missing: "<red>No stall named {stall}"
  recount:
    ok: "<green>Stall {stall} entity count rescanned ({total} entities)"
    missing: "<red>No stall named {stall}"
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew test --tests "net.badgersmc.em.infrastructure.commands.AdminCommandsEntityLimitTest" -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: PASS

- [ ] **Step 6: Build to confirm AdminCommands DI compiles**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew compileKotlin -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: BUILD SUCCESSFUL. Existing `AdminCommandsTest` may need the two new constructor args — fix by passing `mockk(relaxed = true)` for `entityCounter` and `regionProvider`.

- [ ] **Step 7: Fix AdminCommandsTest constructor if broken, then full entity-limit verify**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew test --tests "net.badgersmc.em.infrastructure.commands.*" -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: PASS

- [ ] **Step 8: Mark TDD-220/221 done + commit**

Update `docs/tasks.md`: TDD-220, TDD-221 → `[x]` with evidence. Then:

```bash
cd /d/BadgersMC-Dev/EnthusiaMarket && git add src/main/kotlin/net/badgersmc/em/infrastructure/commands/AdminCommands.kt src/main/resources/lang/en_US.yml src/test/kotlin/net/badgersmc/em/infrastructure/commands/AdminCommandsEntityLimitTest.kt docs/tasks.md
git commit -m "feat(commands): /em stall setkind|entitylimit|recount (TDD-220/221)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Workstream C — Region info card (TDD-230)

**File structure:**
- Create: `src/main/kotlin/net/badgersmc/em/domain/ports/RegionBounds.kt` — value type (or nest in RegionProvider).
- Modify: `src/main/kotlin/net/badgersmc/em/domain/ports/RegionProvider.kt` — add `bounds`.
- Modify: `src/main/kotlin/net/badgersmc/em/infrastructure/worldguard/WorldGuardRegionProvider.kt` — implement `bounds`.
- Create: `src/main/kotlin/net/badgersmc/em/application/StallInfoService.kt` — builds StallInfo.
- Create: `src/main/kotlin/net/badgersmc/em/application/StallInfo.kt` — structured DTO.
- Modify: `src/main/kotlin/net/badgersmc/em/infrastructure/commands/AdminCommands.kt` — `/em stall info`.
- Modify: `src/main/kotlin/net/badgersmc/em/infrastructure/listeners/PurchaseSignClickListener.kt` — INFO sign branch.
- Modify: `src/main/resources/lang/en_US.yml` — stall.info.* keys.

### Task C.1: RegionBounds + RegionProvider.bounds

**Files:**
- Modify: `src/main/kotlin/net/badgersmc/em/domain/ports/RegionProvider.kt`
- Modify: `src/main/kotlin/net/badgersmc/em/infrastructure/worldguard/WorldGuardRegionProvider.kt`
- Test: `src/test/kotlin/net/badgersmc/em/domain/ports/RegionBoundsTest.kt`

- [ ] **Step 1: Write the failing test for RegionBounds dimensions**

```kotlin
package net.badgersmc.em.domain.ports

import kotlin.test.Test
import kotlin.test.assertEquals

class RegionBoundsTest {

    @Test fun `dimensions are inclusive block spans`() {
        val b = RegionProvider.RegionBounds(minX = -52, minY = 120, minZ = -279, maxX = -47, maxY = 126, maxZ = -274)
        assertEquals(6, b.width)   // -52..-47 inclusive = 6
        assertEquals(7, b.height)  // 120..126 inclusive = 7
        assertEquals(6, b.length)  // -279..-274 inclusive = 6
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew test --tests "net.badgersmc.em.domain.ports.RegionBoundsTest" -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: FAIL — `RegionBounds` not defined.

- [ ] **Step 3: Add RegionBounds + bounds() to RegionProvider**

In `RegionProvider.kt`, add the nested data class and method:

```kotlin
    /** Inclusive cuboid bounds of a region; dimensions are block spans. */
    data class RegionBounds(
        val minX: Int, val minY: Int, val minZ: Int,
        val maxX: Int, val maxY: Int, val maxZ: Int,
    ) {
        val width: Int get() = maxX - minX + 1
        val height: Int get() = maxY - minY + 1
        val length: Int get() = maxZ - minZ + 1
    }

    /** Bounds of region [id] in [world], or null when absent. */
    fun bounds(world: String, id: String): RegionBounds?
```

- [ ] **Step 4: Implement bounds() in WorldGuardRegionProvider**

```kotlin
    override fun bounds(world: String, id: String): RegionProvider.RegionBounds? {
        val bukkitWorld = Bukkit.getWorld(world) ?: return null
        val container = WorldGuard.getInstance().platform.regionContainer
        val regionManager = container.get(
            com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(bukkitWorld)
        ) ?: return null
        val region = regionManager.getRegion(id) ?: return null
        val min = region.minimumPoint
        val max = region.maximumPoint
        return RegionProvider.RegionBounds(
            minX = min.x(), minY = min.y(), minZ = min.z(),
            maxX = max.x(), maxY = max.y(), maxZ = max.z(),
        )
    }
```

Note: WorldEdit 7.3.0 `BlockVector3` exposes `.x()`, `.y()`, `.z()`. If the repo's WE version uses `.getX()`, adjust. Confirm via compile.

- [ ] **Step 5: Run the test + compile to verify pass**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew test --tests "net.badgersmc.em.domain.ports.RegionBoundsTest" compileKotlin -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: PASS + BUILD SUCCESSFUL. Fix any mock breakage from the new `bounds`/`regionAt` interface methods (relaxed mocks auto-stub).

- [ ] **Step 6: Commit**

```bash
cd /d/BadgersMC-Dev/EnthusiaMarket && git add src/main/kotlin/net/badgersmc/em/domain/ports/RegionProvider.kt src/main/kotlin/net/badgersmc/em/infrastructure/worldguard/WorldGuardRegionProvider.kt src/test/kotlin/net/badgersmc/em/domain/ports/RegionBoundsTest.kt
git commit -m "feat(domain): RegionProvider.bounds + RegionBounds dimensions (TDD-230)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

### Task C.2: StallInfo DTO + StallInfoService

**Files:**
- Create: `src/main/kotlin/net/badgersmc/em/application/StallInfo.kt`
- Create: `src/main/kotlin/net/badgersmc/em/application/StallInfoService.kt`
- Test: `src/test/kotlin/net/badgersmc/em/application/StallInfoServiceTest.kt`

- [ ] **Step 1: Write the failing test (asserts all 9 fields populated)**

```kotlin
package net.badgersmc.em.application

import io.mockk.every
import io.mockk.mockk
import net.badgersmc.em.domain.ports.RegionProvider
import net.badgersmc.em.domain.stall.*
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StallInfoServiceTest {

    @Test fun `builds info card with all fields for an owned stall`() {
        val stalls = mockk<StallRepository>()
        val regions = mockk<RegionProvider>()
        val owners = mockk<OwnerNameResolver>(relaxed = true)
        every { owners.displayNameFor(any()) } returns "Steve"

        val ownerUuid = java.util.UUID.randomUUID()
        val stall = Stall(
            id = StallId("stall1"), regionId = "stall1", world = "world",
            state = StallState.OWNED, owner = OwnerRef(OwnerType.SOLO, ownerUuid.toString()),
            ownerSince = Instant.now(), winningBid = 1000L, rentTerms = RentTerms.formula(1.0),
            members = setOf(java.util.UUID.randomUUID()),
            nextRentAt = Instant.now().plusSeconds(3600), kind = "shop",
        )
        every { stalls.findById(StallId("stall1")) } returns stall
        every { regions.bounds("world", "stall1") } returns
            RegionProvider.RegionBounds(-52, 120, -279, -47, 126, -274)

        val service = StallInfoService(stalls, regions, owners)
        val info = service.infoFor(StallId("stall1"))!!

        assertEquals("stall1", info.stallId)
        assertEquals("shop", info.kind)
        assertEquals("Steve", info.ownerName)
        assertEquals(1, info.memberCount)
        assertEquals(StallState.OWNED, info.state)
        assertEquals(false, info.available)
        assertNotNull(info.nextRentAt)
        assertEquals("6x7x6", "${info.width}x${info.height}x${info.length}")
        assertTrue(info.currentRent >= 0)
    }

    @Test fun `unowned stall is available`() {
        val stalls = mockk<StallRepository>()
        val regions = mockk<RegionProvider>(relaxed = true)
        val owners = mockk<OwnerNameResolver>(relaxed = true)
        val stall = Stall(
            id = StallId("stall2"), regionId = "stall2", world = "world",
            state = StallState.UNOWNED, owner = OwnerRef.unowned(),
            ownerSince = null, winningBid = 0L, rentTerms = RentTerms.formula(1.0),
        )
        every { stalls.findById(StallId("stall2")) } returns stall
        every { regions.bounds(any(), any()) } returns
            RegionProvider.RegionBounds(0, 0, 0, 4, 4, 4)
        val info = StallInfoService(stalls, regions, owners).infoFor(StallId("stall2"))!!
        assertEquals(true, info.available)
    }

    @Test fun `returns null for missing stall`() {
        val stalls = mockk<StallRepository>()
        every { stalls.findById(StallId("nope")) } returns null
        val service = StallInfoService(stalls, mockk(relaxed = true), mockk(relaxed = true))
        assertEquals(null, service.infoFor(StallId("nope")))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew test --tests "net.badgersmc.em.application.StallInfoServiceTest" -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: FAIL — `StallInfo`/`StallInfoService` not defined.

- [ ] **Step 3: Implement StallInfo DTO**

```kotlin
package net.badgersmc.em.application

import net.badgersmc.em.domain.stall.StallState
import java.time.Instant

/**
 * Structured region info card data (REQ-230). All nine player-facing fields
 * are computed in the application layer so the infra renderer only formats.
 */
data class StallInfo(
    val stallId: String,
    val kind: String,
    val ownerName: String,
    val memberCount: Int,
    val currentRent: Long,
    val nextRentAt: Instant?,
    val width: Int,
    val height: Int,
    val length: Int,
    val state: StallState,
    val available: Boolean,
)
```

- [ ] **Step 4: Implement StallInfoService**

Check `OwnerNameResolver.displayNameFor` exists (it does — referenced in PurchaseSignRenderer). Check `RentTerms.amountFor`/`pct` for the rent figure. The service computes current rent from the stall's rent terms and winning bid.

```kotlin
package net.badgersmc.em.application

import net.badgersmc.em.domain.ports.RegionProvider
import net.badgersmc.em.domain.stall.Stall
import net.badgersmc.em.domain.stall.StallId
import net.badgersmc.em.domain.stall.StallRepository
import net.badgersmc.em.domain.stall.StallState
import net.badgersmc.nexus.annotations.Service

/**
 * Builds the structured [StallInfo] card for a stall (REQ-230/231). Pulls
 * geometry from [RegionProvider.bounds] and owner display name from
 * [OwnerNameResolver]; all formatting is left to the infra renderer.
 */
@Service
class StallInfoService(
    private val stalls: StallRepository,
    private val regions: RegionProvider,
    private val owners: OwnerNameResolver,
) {
    fun infoFor(id: StallId): StallInfo? {
        val stall = stalls.findById(id) ?: return null
        val bounds = regions.bounds(stall.world, stall.regionId)
        return StallInfo(
            stallId = stall.id.value,
            kind = stall.kind,
            ownerName = ownerName(stall),
            memberCount = stall.members.size,
            currentRent = currentRent(stall),
            nextRentAt = stall.nextRentAt,
            width = bounds?.width ?: 0,
            height = bounds?.height ?: 0,
            length = bounds?.length ?: 0,
            state = stall.state,
            available = stall.state == StallState.UNOWNED,
        )
    }

    private fun ownerName(stall: Stall): String =
        if (stall.owner.type == net.badgersmc.em.domain.stall.OwnerType.NONE) "—"
        else owners.displayNameFor(stall.owner)

    private fun currentRent(stall: Stall): Long =
        stall.rentTerms.amountFor(stall.winningBid)
}
```

Note: verify the exact signature of `OwnerNameResolver.displayNameFor` (it may take `OwnerRef` or `UUID`) and `RentTerms.amountFor` by reading those files before implementing. Adjust the two calls to match. If `displayNameFor` takes a `UUID`, resolve `stall.owner.id` to UUID for SOLO and use a guild path for GUILD — mirror `PurchaseSignRenderer`'s usage.

- [ ] **Step 5: Run the test to verify it passes**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew test --tests "net.badgersmc.em.application.StallInfoServiceTest" -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: PASS (adjust the test's `owners` mock to match the real `displayNameFor` signature if you changed it).

- [ ] **Step 6: Commit**

```bash
cd /d/BadgersMC-Dev/EnthusiaMarket && git add src/main/kotlin/net/badgersmc/em/application/StallInfo.kt src/main/kotlin/net/badgersmc/em/application/StallInfoService.kt src/test/kotlin/net/badgersmc/em/application/StallInfoServiceTest.kt
git commit -m "feat(application): StallInfoService builds 9-field info card (TDD-230)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

### Task C.3: Wire info card to command + INFO sign

**Files:**
- Modify: `src/main/kotlin/net/badgersmc/em/infrastructure/commands/AdminCommands.kt` — `/em stall info`.
- Modify: `src/main/kotlin/net/badgersmc/em/infrastructure/listeners/PurchaseSignClickListener.kt` — INFO branch + renderer.
- Modify: `src/main/resources/lang/en_US.yml` — `stall.info.card` key.
- Test: `src/test/kotlin/net/badgersmc/em/infrastructure/listeners/PurchaseSignClickInfoTest.kt`

- [ ] **Step 1: Add the stall.info.card lang key**

In `en_US.yml`, under `stall:`, add:

```yaml
  info:
    card: |
      <gold>━━━ Stall {stall} ━━━
      <gray>Kind: <white>{kind}  <gray>State: <white>{state}
      <gray>Owner: <white>{owner}  <gray>Members: <white>{members}
      <gray>Rent: <white>{rent}  <gray>Next: <white>{next}
      <gray>Size: <white>{width}x{height}x{length}
      <gray>Available: <white>{available}
```

- [ ] **Step 2: Add `/em stall info` subcommand**

Inject `StallInfoService` into `AdminCommands` constructor (add `private val stallInfo: net.badgersmc.em.application.StallInfoService,`). Add:

```kotlin
    @Subcommand("stall info")
    @Permission("enthusiamarket.stall.info")
    fun stallInfo(@Context sender: CommandSender, @Arg stallId: String) {
        val info = stallInfo.infoFor(StallId(stallId))
        if (info == null) {
            sender.sendMessage(lang.msg("stall.info.missing", "stall" to stallId))
            return
        }
        sender.sendMessage(renderInfoCard(info))
    }

    private fun renderInfoCard(info: net.badgersmc.em.application.StallInfo) = lang.msg(
        "stall.info.card",
        "stall" to info.stallId,
        "kind" to info.kind,
        "state" to info.state.name,
        "owner" to info.ownerName,
        "members" to info.memberCount,
        "rent" to info.currentRent,
        "next" to (info.nextRentAt?.toString() ?: "—"),
        "width" to info.width,
        "height" to info.height,
        "length" to info.length,
        "available" to if (info.available) "yes" else "no",
    )
```

Also add `stall.info.missing` to en_US.yml:
```yaml
    missing: "<red>No stall named {stall}"
```
(place under the `info:` block).

- [ ] **Step 3: Write the failing test for INFO sign routing**

A new INFO `PurchaseSign.kind` value drives the branch. Check the existing `PurchaseSign` kind enum (referenced as BUY|RENT|EXTEND|INFO in TDD-250). The click listener must route a non-owner right-click on an INFO sign to the info card. Test the routing decision via an extracted helper:

```kotlin
package net.badgersmc.em.infrastructure.listeners

import net.badgersmc.em.application.StallInfo
import net.badgersmc.em.domain.stall.StallState
import kotlin.test.Test
import kotlin.test.assertTrue

class PurchaseSignClickInfoTest {

    @Test fun `info card text contains all required fields`() {
        val info = StallInfo(
            stallId = "stall1", kind = "shop", ownerName = "Steve", memberCount = 2,
            currentRent = 100, nextRentAt = null, width = 6, height = 7, length = 6,
            state = StallState.OWNED, available = false,
        )
        val text = PurchaseSignClickListener.infoCardPlaceholders(info)
        assertTrue(text.containsKey("stall"))
        assertTrue(text.containsKey("kind"))
        assertTrue(text.containsKey("owner"))
        assertTrue(text.containsKey("members"))
        assertTrue(text.containsKey("rent"))
        assertTrue(text.containsKey("state"))
        assertTrue(text.containsKey("width"))
        assertTrue(text.containsKey("available"))
    }
}
```

- [ ] **Step 4: Run the test to verify it fails**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew test --tests "net.badgersmc.em.infrastructure.listeners.PurchaseSignClickInfoTest" -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: FAIL — `infoCardPlaceholders` not defined.

- [ ] **Step 5: Add the INFO branch + companion helper to PurchaseSignClickListener**

Inject `StallInfoService` into the listener constructor (add `private val stallInfo: net.badgersmc.em.application.StallInfoService,`). Add a companion with the pure placeholder builder, and handle the INFO sign kind. First check `PurchaseSign` has a `kind` property and an `INFO` value — if the sign model stores kind, branch on it; the spec says signs have `kind: BUY|RENT|EXTEND|INFO`. In `onClick`, before the `when (stall.state)` block, add:

```kotlin
        // INFO signs always show the info card regardless of stall state.
        if (sign.kind == net.badgersmc.em.domain.sign.PurchaseSign.Kind.INFO) {
            player.sendMessage(lang.msg("stall.info.card", *infoCardPairs(sign.stallId.value)))
            return
        }
```

Wait — to keep the listener testable and avoid Bukkit, add the companion:

```kotlin
    companion object {
        /** Placeholder map for the info card (exposed for testing the field set). */
        fun infoCardPlaceholders(info: net.badgersmc.em.application.StallInfo): Map<String, Any> = mapOf(
            "stall" to info.stallId,
            "kind" to info.kind,
            "state" to info.state.name,
            "owner" to info.ownerName,
            "members" to info.memberCount,
            "rent" to info.currentRent,
            "next" to (info.nextRentAt?.toString() ?: "—"),
            "width" to info.width,
            "height" to info.height,
            "length" to info.length,
            "available" to if (info.available) "yes" else "no",
        )
    }
```

And the INFO branch in `onClick` (after resolving `stall`, before `when (stall.state)`):

```kotlin
        if (sign.kind == net.badgersmc.em.domain.sign.PurchaseSign.Kind.INFO) {
            val info = stallInfo.infoFor(sign.stallId)
            if (info != null) {
                val pairs = infoCardPlaceholders(info).entries.map { it.key to it.value }.toTypedArray()
                player.sendMessage(lang.msg("stall.info.card", *pairs))
            }
            return
        }
```

Note: confirm `PurchaseSign.Kind` enum exists with an `INFO` member by reading `src/main/kotlin/net/badgersmc/em/domain/sign/PurchaseSign.kt`. If the kind type/name differs, adjust. If signs don't currently carry a kind, this branch keys off it being INFO — verify the model supports it (TDD-250 specified it).

- [ ] **Step 6: Run the test to verify it passes**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew test --tests "net.badgersmc.em.infrastructure.listeners.PurchaseSignClickInfoTest" -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: PASS

- [ ] **Step 7: Build + fix any constructor breakage in PurchaseSignClickListener tests**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew compileTestKotlin -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: BUILD SUCCESSFUL. If existing PurchaseSignClickListener tests fail to construct, add the `stallInfo` mock arg.

- [ ] **Step 8: Mark TDD-230 done + commit**

Update `docs/tasks.md`: TDD-230 → `[x]`. Then:

```bash
cd /d/BadgersMC-Dev/EnthusiaMarket && git add src/main/kotlin/net/badgersmc/em/infrastructure/commands/AdminCommands.kt src/main/kotlin/net/badgersmc/em/infrastructure/listeners/PurchaseSignClickListener.kt src/main/resources/lang/en_US.yml src/test/kotlin/net/badgersmc/em/infrastructure/listeners/PurchaseSignClickInfoTest.kt docs/tasks.md
git commit -m "feat(info): /em stall info + INFO sign routing (TDD-230/231)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Workstream D — Particle outline (TDD-240)

**File structure:**
- Create: `src/main/kotlin/net/badgersmc/em/application/ParticleBorderService.kt` — outline tracking + global budget.
- Modify: `src/main/kotlin/net/badgersmc/em/infrastructure/commands/AdminCommands.kt` — `/em stall outline`.
- Modify: `src/main/resources/lang/en_US.yml` — stall.outline.* keys.

The budget math is pure and unit-tested; the Bukkit particle-spawn + repeat-task are thin wrappers driven by the pure planner.

### Task D.1: ParticleBorderService budget planner (pure)

**Files:**
- Create: `src/main/kotlin/net/badgersmc/em/application/ParticleBorderService.kt`
- Test: `src/test/kotlin/net/badgersmc/em/application/ParticleBorderServiceTest.kt`

- [ ] **Step 1: Write the failing test for the budget planner**

```kotlin
package net.badgersmc.em.application

import net.badgersmc.em.domain.ports.RegionProvider.RegionBounds
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class ParticleBorderServiceTest {

    @Test fun `total particles never exceed maxPerTick`() {
        val outlines = listOf(
            RegionBounds(0, 0, 0, 10, 5, 10),
            RegionBounds(0, 0, 0, 50, 20, 50),
        )
        val plan = ParticleBorderService.planParticles(outlines, maxPerTick = 200)
        val total = plan.sumOf { it.points.size }
        assertTrue(total <= 200, "total $total exceeded budget 200")
    }

    @Test fun `single small region within budget gets dense outline`() {
        val outlines = listOf(RegionBounds(0, 0, 0, 4, 4, 4))
        val plan = ParticleBorderService.planParticles(outlines, maxPerTick = 200)
        assertTrue(plan.single().points.isNotEmpty())
        assertTrue(plan.single().points.size <= 200)
    }

    @Test fun `empty outline list yields empty plan`() {
        assertEquals(emptyList(), ParticleBorderService.planParticles(emptyList(), maxPerTick = 200))
    }

    @Test fun `zero budget yields no points`() {
        val outlines = listOf(RegionBounds(0, 0, 0, 10, 10, 10))
        val plan = ParticleBorderService.planParticles(outlines, maxPerTick = 0)
        assertEquals(0, plan.sumOf { it.points.size })
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew test --tests "net.badgersmc.em.application.ParticleBorderServiceTest" -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: FAIL — `ParticleBorderService` not defined.

- [ ] **Step 3: Implement the pure planner**

```kotlin
package net.badgersmc.em.application

import net.badgersmc.em.domain.ports.RegionProvider.RegionBounds
import net.badgersmc.nexus.annotations.Component

/**
 * Plans + tracks player stall outlines (REQ-240/241). The particle budget
 * is split as a single global spacing: total perimeter across all active
 * outlines is sampled at a spacing chosen so the summed point count never
 * exceeds maxPerTick. Degrades by widening spacing, never by dropping an
 * outline. Pure planning is unit-tested; Bukkit spawn is a thin wrapper.
 */
@Component
class ParticleBorderService {

    /** A planned outline: the world-space points to spawn a particle at. */
    data class OutlinePlan(val points: List<Triple<Double, Double, Double>>)

    companion object {
        /**
         * Plan particle points for every outline so total points <= [maxPerTick].
         * Spacing is uniform across all outlines (global density knob).
         */
        fun planParticles(outlines: List<RegionBounds>, maxPerTick: Int): List<OutlinePlan> {
            if (outlines.isEmpty() || maxPerTick <= 0) {
                return outlines.map { OutlinePlan(emptyList()) }.takeIf { outlines.isNotEmpty() && maxPerTick <= 0 }
                    ?: emptyList()
            }
            val totalPerimeter = outlines.sumOf { perimeter(it) }
            if (totalPerimeter <= 0.0) return outlines.map { OutlinePlan(emptyList()) }
            // spacing so that totalPerimeter / spacing <= maxPerTick.
            val spacing = (totalPerimeter / maxPerTick).coerceAtLeast(1.0)
            return outlines.map { OutlinePlan(edgePoints(it, spacing)) }
        }

        /** Sum of the 12 edge lengths of the cuboid (block spans). */
        private fun perimeter(b: RegionBounds): Double {
            val w = b.width.toDouble()
            val h = b.height.toDouble()
            val l = b.length.toDouble()
            return 4.0 * (w + h + l)
        }

        /** Sample points along the 12 cuboid edges at [spacing] intervals. */
        private fun edgePoints(b: RegionBounds, spacing: Double): List<Triple<Double, Double, Double>> {
            val pts = mutableListOf<Triple<Double, Double, Double>>()
            val xs = listOf(b.minX.toDouble(), b.maxX.toDouble())
            val ys = listOf(b.minY.toDouble(), b.maxY.toDouble())
            val zs = listOf(b.minZ.toDouble(), b.maxZ.toDouble())
            // Edges along X (vary x, fixed y,z corners)
            for (y in ys) for (z in zs) sampleLine(b.minX.toDouble(), b.maxX.toDouble(), spacing) { x -> pts.add(Triple(x, y, z)) }
            // Edges along Y
            for (x in xs) for (z in zs) sampleLine(b.minY.toDouble(), b.maxY.toDouble(), spacing) { y -> pts.add(Triple(x, y, z)) }
            // Edges along Z
            for (x in xs) for (y in ys) sampleLine(b.minZ.toDouble(), b.maxZ.toDouble(), spacing) { z -> pts.add(Triple(x, y, z)) }
            return pts
        }

        private inline fun sampleLine(from: Double, to: Double, spacing: Double, emit: (Double) -> Unit) {
            var p = from
            while (p <= to) {
                emit(p)
                p += spacing
            }
        }
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew test --tests "net.badgersmc.em.application.ParticleBorderServiceTest" -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: PASS. If the budget assertion fails because corner sampling overshoots, the spacing formula may need `maxPerTick - (12 * outlines)` headroom for endpoint inclusion — adjust spacing to `(totalPerimeter / (maxPerTick * 0.8))` and re-run until total ≤ budget. Keep the test as the gate.

- [ ] **Step 5: Commit**

```bash
cd /d/BadgersMC-Dev/EnthusiaMarket && git add src/main/kotlin/net/badgersmc/em/application/ParticleBorderService.kt src/test/kotlin/net/badgersmc/em/application/ParticleBorderServiceTest.kt
git commit -m "feat(application): ParticleBorderService global-budget planner (TDD-240)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

### Task D.2: Bukkit task + outline command

**Files:**
- Modify: `src/main/kotlin/net/badgersmc/em/application/ParticleBorderService.kt` — add active-outline tracking + Bukkit spawn.
- Modify: `src/main/kotlin/net/badgersmc/em/infrastructure/commands/AdminCommands.kt` — `/em stall outline`.
- Modify: `src/main/resources/lang/en_US.yml` — stall.outline.* keys.
- Test: `src/test/kotlin/net/badgersmc/em/application/ParticleBorderTrackingTest.kt`

- [ ] **Step 1: Write the failing test for active-outline tracking + expiry**

```kotlin
package net.badgersmc.em.application

import net.badgersmc.em.domain.ports.RegionProvider.RegionBounds
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class ParticleBorderTrackingTest {

    @Test fun `add tracks an outline and expireDue removes expired`() {
        val svc = ParticleBorderService()
        val player = UUID.randomUUID()
        val now = Instant.now()
        svc.addOutline(player, "stall1", RegionBounds(0, 0, 0, 4, 4, 4), expiresAt = now.plusSeconds(10))
        assertEquals(1, svc.activeCount())
        svc.purgeExpired(now.plusSeconds(11))
        assertEquals(0, svc.activeCount())
    }

    @Test fun `re-adding same player+stall replaces the entry`() {
        val svc = ParticleBorderService()
        val player = UUID.randomUUID()
        val now = Instant.now()
        svc.addOutline(player, "stall1", RegionBounds(0, 0, 0, 4, 4, 4), now.plusSeconds(10))
        svc.addOutline(player, "stall1", RegionBounds(0, 0, 0, 4, 4, 4), now.plusSeconds(20))
        assertEquals(1, svc.activeCount())
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew test --tests "net.badgersmc.em.application.ParticleBorderTrackingTest" -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: FAIL — `addOutline`/`activeCount`/`purgeExpired` not defined.

- [ ] **Step 3: Add tracking to ParticleBorderService**

Add to the `ParticleBorderService` class body (instance state, not companion):

```kotlin
    private data class ActiveOutline(
        val player: java.util.UUID,
        val stallId: String,
        val bounds: net.badgersmc.em.domain.ports.RegionProvider.RegionBounds,
        val expiresAt: java.time.Instant,
    )

    private val active = java.util.concurrent.ConcurrentHashMap<Pair<java.util.UUID, String>, ActiveOutline>()

    fun addOutline(
        player: java.util.UUID,
        stallId: String,
        bounds: net.badgersmc.em.domain.ports.RegionProvider.RegionBounds,
        expiresAt: java.time.Instant,
    ) {
        active[player to stallId] = ActiveOutline(player, stallId, bounds, expiresAt)
    }

    fun activeCount(): Int = active.size

    fun purgeExpired(now: java.time.Instant) {
        active.entries.removeIf { it.value.expiresAt.isBefore(now) }
    }

    /** Bounds of all active outlines, for the per-tick planner. */
    fun activeBounds(): List<net.badgersmc.em.domain.ports.RegionProvider.RegionBounds> =
        active.values.map { it.bounds }
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew test --tests "net.badgersmc.em.application.ParticleBorderTrackingTest" -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: PASS

- [ ] **Step 5: Add the outline command + Bukkit render task**

In `AdminCommands.kt`, inject `ParticleBorderService`, `RegionProvider` (already added in B.7), `EnthusiaMarketConfig` (present), and the plugin (present). Add:

```kotlin
    @Subcommand("stall outline")
    @Permission("enthusiamarket.stall.outline")
    fun stallOutline(@Context sender: CommandSender, @Arg stallId: String, @Arg seconds: Int) {
        val player = sender as? Player ?: run {
            sender.sendMessage(lang.msg("stall.outline.player_only"))
            return
        }
        val stall = stalls.findById(StallId(stallId))
        if (stall == null) {
            sender.sendMessage(lang.msg("stall.outline.missing", "stall" to stallId))
            return
        }
        val bounds = regionProvider.bounds(stall.world, stall.regionId)
        if (bounds == null) {
            sender.sendMessage(lang.msg("stall.outline.no_region", "stall" to stallId))
            return
        }
        val dur = if (seconds <= 0) 10 else seconds
        particleBorders.addOutline(
            player.uniqueId, stallId, bounds,
            java.time.Instant.now().plusSeconds(dur.toLong())
        )
        sender.sendMessage(lang.msg("stall.outline.ok", "stall" to stallId, "seconds" to dur))
    }
```

The repeating render task is started once in `onEnable`. Add to `EnthusiaMarket.kt` after listener registration:

```kotlin
        // Particle outline render loop (REQ-240/241): every 4 ticks, plan
        // points within the global budget and spawn END_ROD per requesting
        // player. Purges expired outlines first.
        val particleService = ctx.getBean<net.badgersmc.em.application.ParticleBorderService>()
        org.bukkit.Bukkit.getScheduler().runTaskTimer(this, Runnable {
            particleService.purgeExpired(java.time.Instant.now())
            particleService.renderTick(cfg.particles.maxPerTick, this)
        }, 0L, 4L)
```

And add `renderTick` to `ParticleBorderService` (the Bukkit-touching method, exercised manually not in unit tests):

```kotlin
    /** Spawn particles for the current tick within [maxPerTick]; END_ROD, per-player. */
    fun renderTick(maxPerTick: Int, plugin: org.bukkit.plugin.Plugin) {
        if (active.isEmpty()) return
        val entries = active.values.toList()
        val plans = planParticles(entries.map { it.bounds }, maxPerTick)
        for ((idx, outline) in entries.withIndex()) {
            val player = org.bukkit.Bukkit.getPlayer(outline.player) ?: continue
            val world = org.bukkit.Bukkit.getWorld(outline.stallWorldOrPlayer(player)) ?: player.world
            for ((x, y, z) in plans[idx].points) {
                player.spawnParticle(org.bukkit.Particle.END_ROD, x + 0.5, y + 0.5, z + 0.5, 1, 0.0, 0.0, 0.0, 0.0)
            }
        }
    }

    private fun ActiveOutline.stallWorldOrPlayer(player: org.bukkit.entity.Player): String = player.world.name
```

Note: outline world tracking — `ActiveOutline` should also store the world. Add `val world: String` to `ActiveOutline` and `addOutline`, sourced from `stall.world` at the command. Simplify `renderTick` to use `outline.world`. Update the tracking test's `addOutline` calls to pass a world string (e.g. `"world"`).

Revisit: to keep the D.1/D.2 tests valid, add `world: String` as the 3rd param of `addOutline(player, stallId, world, bounds, expiresAt)` and update `ParticleBorderTrackingTest` calls accordingly before running. Keep `renderTick` using `outline.world`.

- [ ] **Step 6: Add the outline lang keys**

In `en_US.yml`, under `stall:`:

```yaml
  outline:
    ok: "<green>Outlining stall {stall} for {seconds}s"
    missing: "<red>No stall named {stall}"
    no_region: "<red>Stall {stall} has no WorldGuard region"
    player_only: "<red>Only players can request an outline"
```

- [ ] **Step 7: Build + full verify**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew detekt test shadowJar -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: BUILD SUCCESSFUL. Fix any AdminCommands test constructor breakage by adding `particleBorders` + other new mock args.

- [ ] **Step 8: Mark TDD-240 done + commit**

Update `docs/tasks.md`: TDD-240 → `[x]`. Then:

```bash
cd /d/BadgersMC-Dev/EnthusiaMarket && git add -A
git commit -m "feat(outline): /em stall outline + END_ROD render loop (TDD-240/241)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Workstream G — Shop creation/edit menus (TDD-52 + TDD-60)

Wire the two `shop.create` placeholder paths to real menus and fix the Bedrock
item-serialization corruption bug. Build order: after C, before D. Depends only on the
existing shop domain.

**Traced codebase facts (confirmed 2026-06-01 — do not re-derive):**
- Shared codec: `net.badgersmc.em.application.ItemStackSerializer` — `serialize(ItemStack): String`, `deserialize(String): ItemStack?`. Reuse; never duplicate base64 logic.
- Held-item precedent: `SignPlaceListener.kt:122-127` — `player.inventory.itemInMainHand`; reject `Material.AIR`/`amount<=0` with `shop.create.no_held_item`; `held.clone().apply { amount = 1 }`; serialize.
- `Shop.sellItem`/`costItem` are base64 ItemStacks. `costItem` is a UI hint only — store `ItemStackSerializer.serialize(ItemStack(Material.EMERALD, 1))`. Real price is `costAmount` (Int, Vault currency via EconomyProvider).
- Platform routing: `MenuFactory.shouldUseBedrockMenus(player): Boolean` (already injected into `ShopInteractListener`). `UiDispatcher.dispatch()` is dead (zero callers) — delete `UiDispatcher` + its test.
- IFramework GUI pattern to mirror: `interaction/gui/PurchaseMenu.kt` (`ChestGui`, `StaticPane`, `GuiItem`, `gui.show(player)`); implements `net.badgersmc.em.interaction.Menu` (`fun open(player: Player)`).
- `Shop` required fields + `init` invariants: `sellAmount>0`, `costAmount>0`, `stallId` non-blank (see `domain/shop/Shop.kt`). `ShopRepository.upsert(shop): Shop`.
- `ShopCreateListener` already resolves stall + container + authority; the placeholder is at the very end (line ~95, `shop.create.menu_placeholder`). It has `shopRepository`, `lang`, `stallRepository`, `guildProvider` injected.
- `ShopInteractListener` line ~58 is the **purchase** path (right-click existing shop). Bedrock branch wrongly sends `shop.create.bedrock_placeholder`; replace with `BedrockPurchaseForm`. Java already opens `PurchaseMenu`.
- `BedrockCreateShopForm` stores `sellItem = itemName` (raw string) — corruption bug. `BedrockPurchaseForm` + `BedrockShopEditForm` already exist. `BedrockMenuBase(player, logger, lang)` base class; `buildForm(): Form`.

**File structure:**
- Create: `src/main/kotlin/net/badgersmc/em/application/ShopFactory.kt` — pure `Shop`-builder (testable, no Bukkit beyond ItemStack).
- Create: `src/main/kotlin/net/badgersmc/em/interaction/gui/CreateShopMenu.kt` — IFramework GUI.
- Modify: `src/main/kotlin/net/badgersmc/em/interaction/bedrock/BedrockCreateShopForm.kt` — base64 sell item, price+amount only.
- Modify: `src/main/kotlin/net/badgersmc/em/infrastructure/listeners/ShopCreateListener.kt` — capture main-hand, route via MenuFactory.
- Modify: `src/main/kotlin/net/badgersmc/em/infrastructure/listeners/ShopInteractListener.kt` — Bedrock purchase form wiring.
- Delete: `src/main/kotlin/net/badgersmc/em/infrastructure/bedrock/UiDispatcher.kt` + `src/test/kotlin/net/badgersmc/em/infrastructure/bedrock/UiDispatcherTest.kt`.
- Modify: `src/main/resources/lang/en_US.yml` — add `gui.shop.create.*`; remove the two `*_placeholder` keys once unused.

### Task G.1: ShopFactory — pure Shop builder

Extracts the `Shop`-construction logic shared by both menu paths so it is unit-testable without Bukkit GUI/Cumulus. Mirrors `SignPlaceListener`'s field mapping exactly.

**Files:**
- Create: `src/main/kotlin/net/badgersmc/em/application/ShopFactory.kt`
- Test: `src/test/kotlin/net/badgersmc/em/application/ShopFactoryTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package net.badgersmc.em.application

import net.badgersmc.em.domain.shop.SignDirection
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.mockbukkit.mockbukkit.MockBukkit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import java.util.UUID

class ShopFactoryTest {

    @BeforeTest fun setup() { MockBukkit.mock() }
    @AfterTest fun teardown() { MockBukkit.unmock() }

    @Test fun `builds a SELL shop with base64 sell item and emerald cost hint`() {
        val sellStack = ItemStack(Material.DIAMOND, 5)
        val sellItemB64 = ItemStackSerializer.serialize(sellStack.clone().apply { amount = 1 })
        val owner = UUID.randomUUID()
        val shop = ShopFactory.build(
            stallId = "stall1", owner = owner, creator = owner,
            signWorld = "world", signX = 1, signY = 2, signZ = 3,
            containerWorld = "world", containerX = 1, containerY = 1, containerZ = 1,
            sellItemBase64 = sellItemB64, sellAmount = 5, price = 100,
            direction = SignDirection.SELL,
        )
        assertEquals("stall1", shop.stallId)
        assertEquals(5, shop.sellAmount)
        assertEquals(100, shop.costAmount)
        assertEquals(SignDirection.SELL, shop.direction)
        // sellItem round-trips to a diamond.
        val decoded = ItemStackSerializer.deserialize(shop.sellItem)
        assertNotNull(decoded)
        assertEquals(Material.DIAMOND, decoded.type)
        // costItem is the emerald UI hint.
        val cost = ItemStackSerializer.deserialize(shop.costItem)
        assertNotNull(cost)
        assertEquals(Material.EMERALD, cost.type)
    }

    @Test fun `price above Int MAX is clamped`() {
        val owner = UUID.randomUUID()
        val sell = ItemStackSerializer.serialize(ItemStack(Material.DIRT, 1))
        val shop = ShopFactory.build(
            stallId = "s", owner = owner, creator = owner,
            signWorld = "world", signX = 0, signY = 0, signZ = 0,
            containerWorld = "world", containerX = 0, containerY = 0, containerZ = 0,
            sellItemBase64 = sell, sellAmount = 1, price = Long.MAX_VALUE,
            direction = SignDirection.SELL,
        )
        assertEquals(Int.MAX_VALUE, shop.costAmount)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew test --tests "net.badgersmc.em.application.ShopFactoryTest" -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: FAIL — `ShopFactory` not defined.

- [ ] **Step 3: Implement ShopFactory**

```kotlin
package net.badgersmc.em.application

import net.badgersmc.em.domain.shop.Shop
import net.badgersmc.em.domain.shop.SignDirection
import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import java.util.UUID

/**
 * Pure builder for [Shop] from menu/form inputs (REQ-012). Centralises the
 * field mapping shared by CreateShopMenu (Java) and BedrockCreateShopForm so
 * both paths produce identical, correct base64-serialised shops. Mirrors the
 * mapping in SignPlaceListener: sellItem is a base64 ItemStack, costItem is an
 * EMERALD UI hint, real price flows through costAmount (Vault).
 */
object ShopFactory {

    @Suppress("LongParameterList")
    fun build(
        stallId: String,
        owner: UUID,
        creator: UUID,
        signWorld: String, signX: Int, signY: Int, signZ: Int,
        containerWorld: String, containerX: Int, containerY: Int, containerZ: Int,
        sellItemBase64: String,
        sellAmount: Int,
        price: Long,
        direction: SignDirection,
    ): Shop = Shop(
        stallId = stallId,
        owner = owner,
        signWorld = signWorld, signX = signX, signY = signY, signZ = signZ,
        containerWorld = containerWorld, containerX = containerX, containerY = containerY, containerZ = containerZ,
        sellItem = sellItemBase64,
        sellAmount = sellAmount,
        costItem = ItemStackSerializer.serialize(ItemStack(Material.EMERALD, 1)),
        costAmount = price.coerceIn(1L, Int.MAX_VALUE.toLong()).toInt(),
        creatorId = creator,
        direction = direction,
    )
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew test --tests "net.badgersmc.em.application.ShopFactoryTest" -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
cd /d/BadgersMC-Dev/EnthusiaMarket && git add src/main/kotlin/net/badgersmc/em/application/ShopFactory.kt src/test/kotlin/net/badgersmc/em/application/ShopFactoryTest.kt
git commit -m "feat(application): ShopFactory pure Shop builder for menus (TDD-52)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

### Task G.2: Fix BedrockCreateShopForm (base64 sell item, price+amount only)

**Files:**
- Modify: `src/main/kotlin/net/badgersmc/em/interaction/bedrock/BedrockCreateShopForm.kt`

- [ ] **Step 1: Replace the form to take a pre-captured base64 sell item**

Rewrite `BedrockCreateShopForm.kt`. The listener captures the main-hand item and passes its base64 + the per-trade amount source; the form asks only price + amount:

```kotlin
package net.badgersmc.em.interaction.bedrock

import net.badgersmc.em.application.ShopFactory
import net.badgersmc.em.domain.shop.ShopRepository
import net.badgersmc.em.domain.shop.SignDirection
import net.badgersmc.nexus.i18n.LangService
import org.bukkit.Location
import org.bukkit.entity.Player
import org.geysermc.cumulus.form.CustomForm
import org.geysermc.cumulus.response.CustomFormResponse
import java.util.UUID
import java.util.logging.Logger

/**
 * Bedrock Cumulus CustomForm for creating a shop (REQ-012, TDD-60).
 *
 * The sell item is the player's main-hand item, captured by the listener and
 * passed in as a base64-serialised ItemStack ([sellItemBase64]) — NOT a raw
 * material name (the previous implementation stored "diamond" which broke
 * deserialization). The form collects only price + per-trade amount.
 */
class BedrockCreateShopForm(
    player: Player,
    private val stallOwner: UUID,
    private val stallId: String,
    private val signLoc: Location,
    private val containerLoc: Location,
    private val sellItemBase64: String,
    private val shopRepository: ShopRepository,
    logger: Logger,
    lang: LangService,
) : BedrockMenuBase(player, logger, lang) {

    override fun buildForm(): CustomForm {
        return CustomForm.builder()
            .title("Create Shop")
            .label("Set your shop's price and amount. Sell item = the item in your hand.")
            .input("Price per trade", "e.g. 100", "100")
            .input("Amount per trade", "e.g. 1", "1")
            .validResultHandler { response: CustomFormResponse ->
                val priceText = response.asInput(1) ?: ""
                val amountText = response.asInput(2) ?: "1"
                val price = priceText.toLongOrNull()
                val amount = amountText.toIntOrNull() ?: 1
                if (price == null || price <= 0 || amount <= 0) {
                    player.sendMessage(lang.legacy("shop.create.invalid_input"))
                    return@validResultHandler
                }
                val shop = ShopFactory.build(
                    stallId = stallId, owner = stallOwner, creator = player.uniqueId,
                    signWorld = signLoc.world?.name ?: "world",
                    signX = signLoc.blockX, signY = signLoc.blockY, signZ = signLoc.blockZ,
                    containerWorld = containerLoc.world?.name ?: "world",
                    containerX = containerLoc.blockX, containerY = containerLoc.blockY, containerZ = containerLoc.blockZ,
                    sellItemBase64 = sellItemBase64, sellAmount = amount, price = price,
                    direction = SignDirection.SELL,
                )
                shopRepository.upsert(shop)
                player.sendMessage(lang.legacy("shop.create.success"))
            }
            .build()
    }
}
```

- [ ] **Step 2: Build to confirm the form compiles**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew compileKotlin -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: BUILD SUCCESSFUL. If `BedrockMenuBaseTest` references the old constructor, it does not (it builds an anonymous subclass) — but if any test constructs `BedrockCreateShopForm` directly, update it to pass `sellItemBase64`.

- [ ] **Step 3: Commit**

```bash
cd /d/BadgersMC-Dev/EnthusiaMarket && git add src/main/kotlin/net/badgersmc/em/interaction/bedrock/BedrockCreateShopForm.kt
git commit -m "fix(bedrock): create form stores base64 sell item, not raw name (TDD-60)

Previously stored sellItem='diamond' which fails deserializeStack at trade
time. Now takes the main-hand item as base64 (captured by listener) and
asks only price + amount.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

### Task G.3: CreateShopMenu (IFramework GUI)

A minimal `ChestGui` mirroring `PurchaseMenu`: shows the captured sell item, a price control, an amount control, and a confirm button. To keep scope tight for release, price/amount use fixed-step +/- buttons with sane defaults (anvil text-entry deferred). The confirm button calls `ShopFactory.build` + `shopRepository.upsert`.

**Files:**
- Create: `src/main/kotlin/net/badgersmc/em/interaction/gui/CreateShopMenu.kt`

- [ ] **Step 1: Implement CreateShopMenu**

```kotlin
package net.badgersmc.em.interaction.gui

import com.github.stefvanschie.inventoryframework.adventuresupport.ComponentHolder
import com.github.stefvanschie.inventoryframework.gui.GuiItem
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui
import com.github.stefvanschie.inventoryframework.pane.StaticPane
import net.badgersmc.em.application.ItemStackSerializer
import net.badgersmc.em.application.ShopFactory
import net.badgersmc.em.domain.shop.ShopRepository
import net.badgersmc.em.domain.shop.SignDirection
import net.badgersmc.nexus.i18n.LangService
import net.badgersmc.em.interaction.Menu
import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.UUID

/**
 * Java IFramework GUI for creating a sign shop (REQ-012, TDD-52). The sell
 * item is the player's main-hand item (captured by the listener, passed as
 * base64). Price + per-trade amount are adjusted with +/- buttons; confirm
 * builds the Shop via [ShopFactory] and persists it.
 */
class CreateShopMenu(
    private val stallId: String,
    private val stallOwner: UUID,
    private val signLoc: Location,
    private val containerLoc: Location,
    private val sellItemBase64: String,
    private val shopRepository: ShopRepository,
    private val lang: LangService,
) : Menu {

    private var price: Long = 100
    private var amount: Int = 1

    override fun open(player: Player) {
        render(player)
    }

    private fun render(player: Player) {
        val gui = ChestGui(3, ComponentHolder.of(lang.msg("gui.shop.create.title")))
        val pane = StaticPane(9, 3)

        // Sell item preview (decoded from base64).
        val preview = ItemStackSerializer.deserialize(sellItemBase64) ?: ItemStack(Material.BARRIER)
        pane.addItem(GuiItem(preview), 2, 1)

        // Price controls.
        pane.addItem(GuiItem(decorated(Material.LIME_DYE, lang.msg("gui.shop.create.price_up", "price" to price))) {
            it.isCancelled = true; price += 10; render(player)
        }, 4, 0)
        pane.addItem(GuiItem(decorated(Material.EMERALD, lang.msg("gui.shop.create.price", "price" to price))), 4, 1)
        pane.addItem(GuiItem(decorated(Material.RED_DYE, lang.msg("gui.shop.create.price_down", "price" to price))) {
            it.isCancelled = true; price = (price - 10).coerceAtLeast(1); render(player)
        }, 4, 2)

        // Amount controls.
        pane.addItem(GuiItem(decorated(Material.LIME_DYE, lang.msg("gui.shop.create.amount_up", "amount" to amount))) {
            it.isCancelled = true; amount += 1; render(player)
        }, 6, 0)
        pane.addItem(GuiItem(decorated(Material.PAPER, lang.msg("gui.shop.create.amount", "amount" to amount))), 6, 1)
        pane.addItem(GuiItem(decorated(Material.RED_DYE, lang.msg("gui.shop.create.amount_down", "amount" to amount))) {
            it.isCancelled = true; amount = (amount - 1).coerceAtLeast(1); render(player)
        }, 6, 2)

        // Confirm.
        pane.addItem(GuiItem(decorated(Material.LIME_STAINED_GLASS_PANE, lang.msg("gui.shop.create.confirm"))) {
            it.isCancelled = true
            val shop = ShopFactory.build(
                stallId = stallId, owner = stallOwner, creator = player.uniqueId,
                signWorld = signLoc.world?.name ?: "world",
                signX = signLoc.blockX, signY = signLoc.blockY, signZ = signLoc.blockZ,
                containerWorld = containerLoc.world?.name ?: "world",
                containerX = containerLoc.blockX, containerY = containerLoc.blockY, containerZ = containerLoc.blockZ,
                sellItemBase64 = sellItemBase64, sellAmount = amount, price = price,
                direction = SignDirection.SELL,
            )
            shopRepository.upsert(shop)
            player.closeInventory()
            player.sendMessage(lang.msg("shop.create.success"))
        }, 8, 2)

        gui.addPane(pane)
        gui.show(player)
    }

    private fun decorated(material: Material, name: Component, lore: List<Component> = emptyList()): ItemStack {
        val item = ItemStack(material)
        val meta = item.itemMeta ?: return item
        meta.displayName(name)
        if (lore.isNotEmpty()) meta.lore(lore)
        item.itemMeta = meta
        return item
    }
}
```

- [ ] **Step 2: Add the gui.shop.create.* lang keys**

In `en_US.yml`, under the `gui:` → `shop:` section (where `gui.shop.title` lives), add a `create:` block:

```yaml
    create:
      title: "<dark_gray>Create Shop"
      price: "<green>Price: <gold>{price}"
      price_up: "<green>+10 (now {price})"
      price_down: "<red>-10 (now {price})"
      amount: "<green>Amount: <gold>{amount}"
      amount_up: "<green>+1 (now {amount})"
      amount_down: "<red>-1 (now {amount})"
      confirm: "<green>Confirm & create shop"
```

- [ ] **Step 3: Build to confirm the menu compiles**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew compileKotlin -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: BUILD SUCCESSFUL. Confirm the `GuiItem { event -> }` click-consumer signature matches the IFramework version in use (mirror `PurchaseMenu`'s `GuiItem(...) { event -> ... }`).

- [ ] **Step 4: Commit**

```bash
cd /d/BadgersMC-Dev/EnthusiaMarket && git add src/main/kotlin/net/badgersmc/em/interaction/gui/CreateShopMenu.kt src/main/resources/lang/en_US.yml
git commit -m "feat(gui): CreateShopMenu IFramework shop-creation GUI (TDD-52)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

### Task G.4: Route ShopCreateListener through MenuFactory

**Files:**
- Modify: `src/main/kotlin/net/badgersmc/em/infrastructure/listeners/ShopCreateListener.kt`
- Test: `src/test/kotlin/net/badgersmc/em/infrastructure/listeners/ShopCreateListenerRoutingTest.kt`

- [ ] **Step 1: Write the failing test for held-item capture + routing decision**

The routing + capture logic is extracted into a pure companion helper so it is testable without firing a real `PlayerInteractEvent`. Test that an empty hand is rejected and a held item yields base64:

```kotlin
package net.badgersmc.em.infrastructure.listeners

import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.mockbukkit.mockbukkit.MockBukkit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertNotNull

class ShopCreateListenerRoutingTest {

    @BeforeTest fun setup() { MockBukkit.mock() }
    @AfterTest fun teardown() { MockBukkit.unmock() }

    @Test fun `empty hand yields null sell item`() {
        val air = ItemStack(Material.AIR)
        assertNull(ShopCreateListener.captureSellItem(air))
    }

    @Test fun `held item yields base64 with amount normalised to one`() {
        val held = ItemStack(Material.DIAMOND, 16)
        val b64 = ShopCreateListener.captureSellItem(held)
        assertNotNull(b64)
        val decoded = net.badgersmc.em.application.ItemStackSerializer.deserialize(b64)
        assertNotNull(decoded)
        kotlin.test.assertEquals(Material.DIAMOND, decoded.type)
        kotlin.test.assertEquals(1, decoded.amount)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew test --tests "net.badgersmc.em.infrastructure.listeners.ShopCreateListenerRoutingTest" -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: FAIL — `captureSellItem` not defined.

- [ ] **Step 3: Add the companion helper + MenuFactory routing to ShopCreateListener**

Inject `MenuFactory` and the plugin `Logger` into the constructor. Add the companion and replace the placeholder line. Update the constructor:

```kotlin
@Component
open class ShopCreateListener(
    private val stallRepository: StallRepository,
    private val shopRepository: ShopRepository,
    private val lang: LangService,
    private val menuFactory: net.badgersmc.em.interaction.MenuFactory,
    private val logger: java.util.logging.Logger,
    private val guildProvider: GuildProvider? = null
) : Listener {
```

Add the companion (after the class opening brace or near the bottom):

```kotlin
    companion object {
        /**
         * Capture the player's main-hand item as a base64 sell item, or null
         * when the hand is empty. Amount is normalised to 1 (per-trade amount
         * is chosen in the menu). Mirrors SignPlaceListener.
         */
        fun captureSellItem(held: ItemStack): String? {
            if (held.type == Material.AIR || held.amount <= 0) return null
            val one = held.clone().apply { amount = 1 }
            return net.badgersmc.em.application.ItemStackSerializer.serialize(one)
        }
    }
```

Add the imports `org.bukkit.inventory.ItemStack` and `org.bukkit.Material` (Material is already imported; add ItemStack). Replace the placeholder block at the end of `onSignInteract`:

```kotlin
        event.setUseInteractedBlock(Event.Result.DENY)

        // Capture the held item as the sell item (REQ-012).
        val sellItemB64 = captureSellItem(event.player.inventory.itemInMainHand)
        if (sellItemB64 == null) {
            event.player.sendMessage(lang.msg("shop.create.no_held_item"))
            return
        }

        val signLoc = block.location
        val containerLoc = attachedBlock.location
        val player = event.player
        if (menuFactory.shouldUseBedrockMenus(player)) {
            net.badgersmc.em.interaction.bedrock.BedrockCreateShopForm(
                player, java.util.UUID.fromString(stall.owner.id.takeIf { stall.owner.type == OwnerType.SOLO } ?: player.uniqueId.toString()),
                stall.id.value, signLoc, containerLoc, sellItemB64, shopRepository, logger, lang,
            ).open(player)
        } else {
            net.badgersmc.em.interaction.gui.CreateShopMenu(
                stall.id.value,
                java.util.UUID.fromString(stall.owner.id.takeIf { stall.owner.type == OwnerType.SOLO } ?: player.uniqueId.toString()),
                signLoc, containerLoc, sellItemB64, shopRepository, lang,
            ).open(player)
        }
```

Note: the shop owner for a GUILD-owned stall is the creating player (shops are player-or-guild scoped via `creatorId`/`guildId`); using `player.uniqueId` when not SOLO matches `SignPlaceListener` (`owner = player.uniqueId`). Simplify to `owner = player.uniqueId` if the guild-shop attribution is handled elsewhere — confirm against `SignPlaceListener` which uses `owner = player.uniqueId` unconditionally. Prefer that: pass `player.uniqueId` as owner for both branches to match existing behaviour.

- [ ] **Step 4: Simplify owner to player.uniqueId (match SignPlaceListener)**

Replace both `java.util.UUID.fromString(...)` owner expressions with `player.uniqueId` to match the proven `SignPlaceListener` mapping (`owner = player.uniqueId`). Final branches:

```kotlin
        if (menuFactory.shouldUseBedrockMenus(player)) {
            net.badgersmc.em.interaction.bedrock.BedrockCreateShopForm(
                player, player.uniqueId, stall.id.value, signLoc, containerLoc,
                sellItemB64, shopRepository, logger, lang,
            ).open(player)
        } else {
            net.badgersmc.em.interaction.gui.CreateShopMenu(
                stall.id.value, player.uniqueId, signLoc, containerLoc,
                sellItemB64, shopRepository, lang,
            ).open(player)
        }
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew test --tests "net.badgersmc.em.infrastructure.listeners.ShopCreateListenerRoutingTest" -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: PASS

- [ ] **Step 6: Build + fix any ShopCreateListener test constructor breakage**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew compileTestKotlin -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: BUILD SUCCESSFUL. If an existing `ShopCreateListenerTest` constructs the listener, add `menuFactory = mockk(relaxed = true)` and `logger = mockk(relaxed = true)` args.

- [ ] **Step 7: Commit**

```bash
cd /d/BadgersMC-Dev/EnthusiaMarket && git add src/main/kotlin/net/badgersmc/em/infrastructure/listeners/ShopCreateListener.kt src/test/kotlin/net/badgersmc/em/infrastructure/listeners/ShopCreateListenerRoutingTest.kt
git commit -m "feat(listeners): ShopCreateListener captures held item + routes to menu (TDD-52)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

### Task G.5: Wire ShopInteractListener Bedrock purchase form + delete UiDispatcher

**Files:**
- Modify: `src/main/kotlin/net/badgersmc/em/infrastructure/listeners/ShopInteractListener.kt`
- Delete: `src/main/kotlin/net/badgersmc/em/infrastructure/bedrock/UiDispatcher.kt`
- Delete: `src/test/kotlin/net/badgersmc/em/infrastructure/bedrock/UiDispatcherTest.kt`

- [ ] **Step 1: Confirm BedrockPurchaseForm constructor signature**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && sed -n '1,40p' src/main/kotlin/net/badgersmc/em/interaction/bedrock/BedrockPurchaseForm.kt`
Expected: shows the constructor params (likely `(player, shop, tradeService, logger, lang)`). Note the exact params for Step 2.

- [ ] **Step 2: Replace the Bedrock placeholder branch with the real form**

In `ShopInteractListener.kt`, the listener needs a `Logger` to construct the form. Add `private val logger: java.util.logging.Logger,` to the constructor. Replace the placeholder branch in `onSignRightClick`:

```kotlin
        if (menuFactory.shouldUseBedrockMenus(player)) {
            openBedrockPurchaseForm(player, shop)
        } else {
            openPurchaseMenu(player, shop)
        }
```

And add the open method (mirroring `openPurchaseMenu`, using the confirmed constructor params from Step 1 — adjust arg order to match):

```kotlin
    /** Open the Bedrock purchase form. Open for testability. */
    open fun openBedrockPurchaseForm(player: Player, shop: Shop) {
        net.badgersmc.em.interaction.bedrock.BedrockPurchaseForm(
            player, shop, tradeService, logger, lang
        ).open(player)
    }
```

If `BedrockPurchaseForm`'s constructor differs from `(player, shop, tradeService, logger, lang)`, adjust to match Step 1's output.

- [ ] **Step 3: Delete the dead UiDispatcher + its test**

```bash
cd /d/BadgersMC-Dev/EnthusiaMarket && git rm src/main/kotlin/net/badgersmc/em/infrastructure/bedrock/UiDispatcher.kt src/test/kotlin/net/badgersmc/em/infrastructure/bedrock/UiDispatcherTest.kt
```

- [ ] **Step 4: Grep for any remaining UiDispatcher references**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && grep -rn "UiDispatcher" src/ || echo "no references"`
Expected: `no references`. If any are found (e.g. a DI registration or import), remove them.

- [ ] **Step 5: Build + fix ShopInteractListener test constructor**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew compileTestKotlin -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: BUILD SUCCESSFUL. Add `logger = mockk(relaxed = true)` to any existing `ShopInteractListener` test construction.

- [ ] **Step 6: Remove the now-unused placeholder lang keys**

In `en_US.yml`, delete `shop.create.menu_placeholder` and `shop.create.bedrock_placeholder` (lines ~130-131). Verify nothing references them:
Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && grep -rn "menu_placeholder\|bedrock_placeholder" src/ || echo "clean"`
Expected: `clean`.

- [ ] **Step 7: Full verify**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew detekt test shadowJar -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: BUILD SUCCESSFUL

- [ ] **Step 8: Mark TDD-52/TDD-60 done + commit**

Update `docs/tasks.md`: mark TDD-52 (CreateShopMenu) and TDD-60 (Bedrock create form) `[x]` with evidence. Then:

```bash
cd /d/BadgersMC-Dev/EnthusiaMarket && git add -A
git commit -m "feat(listeners): wire Bedrock purchase form; delete dead UiDispatcher (TDD-60)

ShopInteractListener Bedrock branch now opens the real BedrockPurchaseForm
instead of a placeholder. UiDispatcher (dead reflection stub, zero callers)
removed in favour of MenuFactory routing. Placeholder lang keys dropped.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Final integration verification

### Task Z.1: Full green build + manual QA checklist

- [ ] **Step 1: Full verify with all gates**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew clean detekt test shadowJar jacocoTestReport -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: BUILD SUCCESSFUL, all tests pass, detekt 0 issues.

- [ ] **Step 2: Verify generated paper-plugin.yml in the shaded jar**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && unzip -p build/libs/EnthusiaMarket-0.1.0.jar paper-plugin.yml | sed -n '/^permissions:/,$p'`
Expected: permissions block present with `enthusiamarket.stall.buy/offer/sellback/members` + new feature nodes.

- [ ] **Step 3: Document the manual QA script (non-op account)**

Append a manual QA checklist to the PR description when opening it. The checklist MUST include (all as a NON-OP player — op bypass masks build bugs):
  1. `/em import` on the production world → message reports `provisioned` ≈ 71. Confirm a previously-dead stall (e.g. `stall66`, priority 0) now has priority 20 + build flags via `/rg info stall66`.
  2. Claim a stall as a non-op player → place a block inside → succeeds.
  3. Place item frames + armor stands → rotate an item frame → succeeds (decoration flags).
  4. Breed villagers past the kind cap → spawn cancelled at cap; under cap allowed.
  5. `/em stall info stall1` → card shows all 9 fields. Right-click an INFO sign as non-owner → same card.
  6. `/em stall outline stall1 10` → END_ROD border visible for 10s, only to requester.
  7. Confirm offer/sellback/buy/members commands work for a normal player (perm drift fix).
  8. Shop create (Java): hold an item, left-click+sneak a container sign in your stall →
     CreateShopMenu opens showing the held item; set price/amount; confirm → shop persists and
     a buyer can trade it. Empty hand → rejected with no-held-item message.
  9. Shop create (Bedrock/Floodgate, or `bedrock.forceForms: true`): same flow → Cumulus form
     asks price+amount only; created shop's sell item is the held item (NOT a raw name) and
     trades correctly (regression test for the deserialization bug).

- [ ] **Step 4: Final commit if any cleanup needed**

```bash
cd /d/BadgersMC-Dev/EnthusiaMarket && git add -A && git commit -m "chore: EM release-readiness final verification

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>" || echo "nothing to commit"
```

---

## Self-Review Notes (for the implementer)

These are known points where the plan depends on signatures that should be confirmed by reading the file before implementing (the plan calls them out inline too):

1. **`OwnerNameResolver.displayNameFor`** (Task C.2) — confirm it takes `OwnerRef` vs `UUID`; mirror `PurchaseSignRenderer`'s call. Adjust StallInfoService + its test mock.
2. **`RentTerms.amountFor`** (Task C.2) — confirm the method name/signature for computing rent from winning bid. The codebase test TDD-20 references `RentTerms.amountFor(stall)`; it may take a Stall or a Long. Adjust `currentRent`.
3. **`PurchaseSign.Kind.INFO`** (Task C.3) — confirm the enum exists with an INFO value (TDD-250 specified BUY|RENT|EXTEND|INFO). If the sign model names it differently, adjust the branch.
4. **WG flag constants** (Task F.2) — `Flags.ITEM_FRAME_ROTATE` exact name; confirm against `com.sk89q.worldguard.protection.flags.Flags`.
5. **WG `getApplicableRegions` / `BlockVector3`** (Task B.6) — confirm signature for WG 7.0.9.
6. **WE `BlockVector3.x()/y()/z()`** (Task C.1) — confirm accessor names for WE 7.3.0 (vs `getX()`).
7. **`KEY_WORLD`/`KEY_REGION_PREFIX`** (Task B.7) — these constants are already referenced in AdminCommands.import; locate their existing declaration and reuse rather than redeclare.
8. **`BedrockPurchaseForm` constructor** (Task G.5) — confirm exact param order (`sed -n '1,40p'` in the task); the `openBedrockPurchaseForm` call must match.
9. **IFramework `GuiItem` click-consumer signature** (Task G.3) — mirror `PurchaseMenu`'s `GuiItem(item) { event -> ... }` form for the IF version in use.
10. **`gui.shop.title` / `gui:` lang section location** (Task G.3) — confirm where the existing `gui.shop.*` keys live so the new `create:` block nests correctly.

Each is a single-symbol confirmation; the surrounding logic is fixed.
