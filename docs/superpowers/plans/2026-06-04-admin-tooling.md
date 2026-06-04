# Admin Tooling Implementation Plan (ItemShops Parity SP5)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Port the ItemShops admin shop-verbs onto EM's Vault-money model as a `/shop admin` subtree — `view`, `info`, `remove`, `fix`, `breakothers` — plus an admin teleport branch in the SP2 search results GUI. One permission node, look-at targeting.

**Architecture:** Hexagonal/SPEAR. New pure units (`AdminBreakMode`, `LookAtShopResolver`, `ShopSignRenderer`) + a `ShopManagementService.adminDelete` + thin glue in `ShopCommands`, `BlockProtectionListener`, `SignPlaceListener`, `ShopEditMenu`, `SearchResultsMenu`.

**Tech Stack:** Kotlin 2.0.0, Nexus DI + commands, IFramework GUIs, JUnit 5 + MockK + MockBukkit, detekt 1.23.8.

**Reference spec:** `docs/superpowers/specs/2026-06-04-itemshops-parity-admin-tooling-design.md`

**Standing rules (every task):**
- Prefix bash with `cd <REPO> &&` (`/d/BadgersMC-Dev/EnthusiaMarket`, or `/opt/data/EnthusiaMarket`). On Hermes' box also prefix gradle with `export JAVA_HOME=/opt/data/jdk-21.0.11+10 &&`.
- Every gradle command includes `-Plumaguilds.jar=<JAR> --no-daemon --console=plain` (`/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar` or `/opt/data/...`).
- LF→CRLF git warnings expected. Branch `feat/admin-tooling`. Do not push (coordinator opens the PR).
- TDD: write failing test, run RED, then GREEN. Commit after every task with the given message.
- Gate rule: compare each `Run:` to `Expected:`; mismatch → STOP, fix, re-run; HALT after 3 tries. Final gate runs on the EXACT committed HEAD.

---

## CONFIRMED API SYMBOLS (verified against the repo — use exactly)

- **`ShopCommands`** (`infrastructure/commands/ShopCommands.kt`) — `@Command(name="shop", aliases=["shops"])`. Current constructor: `(management: ShopManagementService, shopRepository: ShopRepository, breakDelete: BreakDeleteMode, search: ShopSearchService, lang: LangService)`. Uses `@Subcommand`, `@Permission`, `@Context sender: CommandSender`, `@net.badgersmc.nexus.commands.annotations.Arg("name") x: T = default`. Multi-segment subcommands with args are already used in `AdminCommands` (`auction start`, `stall members add`) — `admin view` etc. are fine.
- **`BreakDeleteMode`** (`application/BreakDeleteMode.kt`) — `@Component`; `enable(UUID, Long, nowMs=System.currentTimeMillis())`, `disable(UUID)`, `isActive(UUID, nowMs=…): Boolean`; companion `parseDurationMs(arg: String?): Long?` (null=off, "on"/absent/garbage=5m, "Nm"=N min). **Reuse `BreakDeleteMode.parseDurationMs` for breakothers** — do not duplicate it.
- **`ShopRepository`** (`domain/shop/ShopRepository.kt`) — `findBySign(world,x,y,z): Shop?`, `findByContainer(world,x,y,z): List<Shop>`, `findById(Long): Shop?`, `delete(Long)`, `all(): List<Shop>`, `findByOwner(UUID): List<Shop>`, `upsert(Shop): Shop`.
- **`ShopManagementService`** (`application/ShopManagementService.kt`) — `@Service(shopRepository)`; has `delete(actor, shopId): Boolean` (owner-checked) and a private null-safe `fireShopDeleted(owner: UUID)` (uses `Bukkit.getServer()?.pluginManager?.callEvent(ShopDeletedEvent(owner))`, no-ops in unit tests). Add `adminDelete` here.
- **`Shop`** (`domain/shop/Shop.kt`) — data class. Fields used: `id: Long`, `owner: UUID`, `signWorld/signX/signY/signZ`, `containerWorld/containerX/containerY/containerZ`, `sellItem: String` (base64), `sellAmount: Int`, `costItem: String`, `costAmount: Int`, `trusted: Set<UUID>`, `frozen: Boolean`, `searchEnabled: Boolean`, `direction: SignDirection`.
- **`SignDirection`** (`domain/shop/SignDirection.kt`) — `enum { BUY, SELL }`.
- **`ItemStackSerializer`** (`application/`) — `deserialize(base64: String): ItemStack?`, `serialize(ItemStack): String`.
- **`ShopEditMenu`** (`interaction/gui/ShopEditMenu.kt`) — constructor `(shop: Shop, shopRepository: ShopRepository, management: ShopManagementService, lang: LangService)`. `open(player)` guards with `if (player.uniqueId != shop.owner && !player.hasPermission("enthusiamarket.admin")) { … return }`. **Widen that guard** (Task 5) to also accept `enthusiamarket.admin.shop`.
- **`SearchResultsMenu`** (`interaction/gui/SearchResultsMenu.kt`) — constructor `(results: List<Shop>, query: String, page: Int, lang: LangService)`. Result icons added via `pane.addItem(GuiItem(icon) { it.isCancelled = true; player.closeInventory(); player.sendMessage(lang.msg("gui.shop.search.clicked", …)) }, idx % 9, idx / 9)`. **Edit that click lambda** (Task 7).
- **`BlockProtectionListener`** (`infrastructure/listeners/BlockProtectionListener.kt`) — `@Component`, constructor `(shopRepository, breakDelete: BreakDeleteMode, management: ShopManagementService, logger: Logger, lang: LangService)`. In `onBlockBreak`, the sign branch does: `if (shop.owner == player.uniqueId && breakDelete.isActive(player.uniqueId)) { management.delete(…); …; return }` then `cancelSignBreak(event, shop, player)`. **Insert the breakothers branch** between those (Task 5).
- **`SignPlaceListener`** (`infrastructure/listeners/SignPlaceListener.kt`) — after building `val shop = Shop(...)` it formats four lines:
  ```kotlin
  val headerColor = if (direction == SignDirection.BUY) NamedTextColor.GOLD else NamedTextColor.AQUA
  event.line(0, AdventureComponent.text("[${direction.name}]", headerColor))
  event.line(1, AdventureComponent.text("${amount}x ${held.type.name.lowercase()}", NamedTextColor.WHITE))
  event.line(2, AdventureComponent.text("$price", NamedTextColor.GOLD))
  event.line(3, AdventureComponent.text("[Shop]", NamedTextColor.GOLD))
  ```
  **Replace those four** with `ShopSignRenderer.lines(...)` applied per index (Task 3). `AdventureComponent` is `net.kyori.adventure.text.Component`; colors `net.kyori.adventure.text.format.NamedTextColor`.
- **`Player.getTargetBlockExact(maxDistance: Int): org.bukkit.block.Block?`** — Bukkit raycast (null if nothing in range). `Block.world.name`, `Block.x/y/z`, `Block.state`.
- **Sign write (fix):** `(block.state as org.bukkit.block.Sign).getSide(org.bukkit.block.sign.Side.FRONT).line(i, component)` then `state.update()`.
- **Teleport:** `player.teleport(org.bukkit.Location(world, x + 0.5, y.toDouble(), z + 0.5, player.location.yaw, player.location.pitch))`; `org.bukkit.Bukkit.getWorld(name): World?`.
- **`LangService`** — `lang.msg("key", "tok" to v)`; placeholders use `<token>` (NEVER `{token}`).
- **Permission DSL** (`build.gradle.kts`) — `node("name", default = Default.OP, description = "…")`; existing admin nodes (`enthusiamarket.admin`, `enthusiamarket.admin.evict`, …) are nearby. `Default` is `net.badgersmc.nexus.permissions.Default`.
- **Lang file** (`src/main/resources/lang/en_US.yml`) — top-level `shop:` block (contains `trade`, `edit`, `protect`, `cmd`); add a `shop.admin` block. `gui.shop.search` already has `title/result/clicked/prev/next`; add `teleported` beside `clicked`. Existing key `shop.cmd.players_only` is reused for console senders.

---

## Task 1: Permission node + AdminBreakMode

**Files:**
- Modify: `build.gradle.kts`
- Create: `src/main/kotlin/net/badgersmc/em/application/AdminBreakMode.kt`
- Test: `src/test/kotlin/net/badgersmc/em/application/AdminBreakModeTest.kt`

- [ ] **Step 1: Add the permission node**

In `build.gradle.kts`, inside the `permissionTree { … }` block, next to the other `enthusiamarket.admin*` standalone nodes, add:

```kotlin
        node("enthusiamarket.admin.shop", default = Default.OP, description = "Admin shop tooling (/shop admin view/info/remove/fix/breakothers + search teleport)")
```

- [ ] **Step 2: Write the failing AdminBreakMode test**

`src/test/kotlin/net/badgersmc/em/application/AdminBreakModeTest.kt`:

```kotlin
package net.badgersmc.em.application

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdminBreakModeTest {

    @Test fun `enable makes it active within the window`() {
        val m = AdminBreakMode()
        val u = UUID.randomUUID()
        m.enable(u, 60_000, nowMs = 0)
        assertTrue(m.isActive(u, nowMs = 1_000))
    }

    @Test fun `disable clears it`() {
        val m = AdminBreakMode()
        val u = UUID.randomUUID()
        m.enable(u, 60_000, nowMs = 0)
        m.disable(u)
        assertFalse(m.isActive(u, nowMs = 1_000))
    }

    @Test fun `expires after the window`() {
        val m = AdminBreakMode()
        val u = UUID.randomUUID()
        m.enable(u, 60_000, nowMs = 0)
        assertFalse(m.isActive(u, nowMs = 60_001))
    }

    @Test fun `unknown player is inactive`() {
        assertFalse(AdminBreakMode().isActive(UUID.randomUUID()))
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew test --tests "net.badgersmc.em.application.AdminBreakModeTest" -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: FAIL — `AdminBreakMode` not defined.

- [ ] **Step 4: Implement AdminBreakMode**

`src/main/kotlin/net/badgersmc/em/application/AdminBreakMode.kt`:

```kotlin
package net.badgersmc.em.application

import net.badgersmc.nexus.annotations.Component
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Ephemeral per-admin "break any shop to delete" mode (ItemShops parity SP5).
 * While active, BlockProtectionListener lets an admin break ANY shop sign and
 * deletes that shop. Not persisted — clears on restart, exactly like the
 * owner-scoped BreakDeleteMode it mirrors. Reuses BreakDeleteMode.parseDurationMs
 * for the arg shape.
 */
@Component
class AdminBreakMode {

    private val expiry = ConcurrentHashMap<UUID, Long>()

    fun enable(player: UUID, durationMs: Long, nowMs: Long = System.currentTimeMillis()) {
        expiry[player] = nowMs + durationMs
    }

    fun disable(player: UUID) {
        expiry.remove(player)
    }

    fun isActive(player: UUID, nowMs: Long = System.currentTimeMillis()): Boolean {
        val until = expiry[player] ?: return false
        if (nowMs > until) {
            expiry.remove(player)
            return false
        }
        return true
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew test --tests "net.badgersmc.em.application.AdminBreakModeTest" -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
cd /d/BadgersMC-Dev/EnthusiaMarket && git add build.gradle.kts src/main/kotlin/net/badgersmc/em/application/AdminBreakMode.kt src/test/kotlin/net/badgersmc/em/application/AdminBreakModeTest.kt
git commit -m "feat(admin): enthusiamarket.admin.shop node + AdminBreakMode (SP5)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 2: LookAtShopResolver

**Files:**
- Create: `src/main/kotlin/net/badgersmc/em/application/LookAtShopResolver.kt`
- Test: `src/test/kotlin/net/badgersmc/em/application/LookAtShopResolverTest.kt`

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/net/badgersmc/em/application/LookAtShopResolverTest.kt`:

```kotlin
package net.badgersmc.em.application

import io.mockk.every
import io.mockk.mockk
import net.badgersmc.em.domain.shop.Shop
import net.badgersmc.em.domain.shop.ShopRepository
import net.badgersmc.em.domain.shop.SignDirection
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LookAtShopResolverTest {

    private fun shop(id: Long) = Shop(
        id = id, stallId = "s", owner = UUID.randomUUID(),
        signWorld = "world", signX = 1, signY = 2, signZ = 3,
        containerWorld = "world", containerX = 1, containerY = 1, containerZ = 1,
        sellItem = "s", sellAmount = 1, costItem = "c", costAmount = 10,
        direction = SignDirection.SELL,
    )

    @Test fun `resolves a sign block to its shop`() {
        val repo = mockk<ShopRepository>()
        every { repo.findBySign("world", 1, 2, 3) } returns shop(7)
        assertEquals(7L, LookAtShopResolver(repo).resolve("world", 1, 2, 3)?.id)
    }

    @Test fun `falls back to the first container shop`() {
        val repo = mockk<ShopRepository>()
        every { repo.findBySign("world", 5, 6, 7) } returns null
        every { repo.findByContainer("world", 5, 6, 7) } returns listOf(shop(9), shop(10))
        assertEquals(9L, LookAtShopResolver(repo).resolve("world", 5, 6, 7)?.id)
    }

    @Test fun `null world resolves to null`() {
        assertNull(LookAtShopResolver(mockk()).resolve(null, 0, 0, 0))
    }

    @Test fun `no shop at the coords resolves to null`() {
        val repo = mockk<ShopRepository>()
        every { repo.findBySign("world", 1, 1, 1) } returns null
        every { repo.findByContainer("world", 1, 1, 1) } returns emptyList()
        assertNull(LookAtShopResolver(repo).resolve("world", 1, 1, 1))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew test --tests "net.badgersmc.em.application.LookAtShopResolverTest" -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: FAIL — `LookAtShopResolver` not defined.

- [ ] **Step 3: Implement LookAtShopResolver**

`src/main/kotlin/net/badgersmc/em/application/LookAtShopResolver.kt`:

```kotlin
package net.badgersmc.em.application

import net.badgersmc.em.domain.shop.Shop
import net.badgersmc.em.domain.shop.ShopRepository
import net.badgersmc.nexus.annotations.Service

/**
 * Resolves the shop an admin is looking at (ItemShops parity SP5). The caller does
 * the Bukkit raycast (Player.getTargetBlockExact) and passes the block's world +
 * coords; this keeps the lookup logic pure and unit-testable. A sign block resolves
 * directly; otherwise the coords are tried as a linked container (first match).
 */
@Service
class LookAtShopResolver(
    private val shops: ShopRepository,
) {
    fun resolve(world: String?, x: Int, y: Int, z: Int): Shop? {
        if (world == null) return null
        shops.findBySign(world, x, y, z)?.let { return it }
        return shops.findByContainer(world, x, y, z).firstOrNull()
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew test --tests "net.badgersmc.em.application.LookAtShopResolverTest" -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
cd /d/BadgersMC-Dev/EnthusiaMarket && git add src/main/kotlin/net/badgersmc/em/application/LookAtShopResolver.kt src/test/kotlin/net/badgersmc/em/application/LookAtShopResolverTest.kt
git commit -m "feat(admin): LookAtShopResolver for look-at shop targeting (SP5)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 3: ShopSignRenderer + SignPlaceListener refactor

**Files:**
- Create: `src/main/kotlin/net/badgersmc/em/application/ShopSignRenderer.kt`
- Test: `src/test/kotlin/net/badgersmc/em/application/ShopSignRendererTest.kt`
- Modify: `src/main/kotlin/net/badgersmc/em/infrastructure/listeners/SignPlaceListener.kt`

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/net/badgersmc/em/application/ShopSignRendererTest.kt`:

```kotlin
package net.badgersmc.em.application

import net.badgersmc.em.domain.shop.SignDirection
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import kotlin.test.Test
import kotlin.test.assertEquals

class ShopSignRendererTest {

    private val plain = PlainTextComponentSerializer.plainText()
    private val r = ShopSignRenderer()

    @Test fun `sell sign renders the four lines`() {
        val lines = r.lines(SignDirection.SELL, "diamond", 5, 100L)
        assertEquals("[SELL]", plain.serialize(lines[0]))
        assertEquals("5x diamond", plain.serialize(lines[1]))
        assertEquals("100", plain.serialize(lines[2]))
        assertEquals("[Shop]", plain.serialize(lines[3]))
    }

    @Test fun `buy sign uses the BUY header`() {
        assertEquals("[BUY]", plain.serialize(r.lines(SignDirection.BUY, "iron_ingot", 1, 5L)[0]))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew test --tests "net.badgersmc.em.application.ShopSignRendererTest" -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: FAIL — `ShopSignRenderer` not defined.

- [ ] **Step 3: Implement ShopSignRenderer**

`src/main/kotlin/net/badgersmc/em/application/ShopSignRenderer.kt`:

```kotlin
package net.badgersmc.em.application

import net.badgersmc.em.domain.shop.SignDirection
import net.badgersmc.nexus.annotations.Service
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor

/**
 * Pure renderer for the four shop-sign lines (ItemShops parity). Extracted from
 * SignPlaceListener so shop creation and `/shop admin fix` produce identical signs.
 * Bukkit-free: the caller passes the already-resolved sell-item material name.
 */
@Service
class ShopSignRenderer {

    /** [SELL]/[BUY] header · `Nx material` · price · [Shop]. */
    fun lines(direction: SignDirection, sellMaterialName: String, sellAmount: Int, price: Long): List<Component> {
        val headerColor = if (direction == SignDirection.BUY) NamedTextColor.GOLD else NamedTextColor.AQUA
        return listOf(
            Component.text("[${direction.name}]", headerColor),
            Component.text("${sellAmount}x $sellMaterialName", NamedTextColor.WHITE),
            Component.text("$price", NamedTextColor.GOLD),
            Component.text("[Shop]", NamedTextColor.GOLD),
        )
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew test --tests "net.badgersmc.em.application.ShopSignRendererTest" -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: PASS

- [ ] **Step 5: Refactor SignPlaceListener to use the renderer**

Read `SignPlaceListener.kt` first. Add `private val signRenderer: net.badgersmc.em.application.ShopSignRenderer,` to its constructor (it's a `@Component`; Nexus injects the `@Service`). Replace the four inline `event.line(...)` formatting lines (the `headerColor` block) with:

```kotlin
        net.badgersmc.em.application.ShopSignRenderer
        val lines = signRenderer.lines(direction, held.type.name.lowercase(), amount, price)
        lines.forEachIndexed { i, c -> event.line(i, c) }
```

(Delete the now-unused `headerColor` val and the four `AdventureComponent.text(...)` lines. Leave the rest of the method — `player.sendMessage(...)`, the `ShopCreatedEvent` fire — untouched. If `NamedTextColor` / `AdventureComponent` become unused imports, remove them to keep detekt happy.)

- [ ] **Step 6: Build**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew compileKotlin compileTestKotlin -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: BUILD SUCCESSFUL. (If a `SignPlaceListener` test constructs it directly, add a `ShopSignRenderer()` arg.)

- [ ] **Step 7: Commit**

```bash
cd /d/BadgersMC-Dev/EnthusiaMarket && git add src/main/kotlin/net/badgersmc/em/application/ShopSignRenderer.kt src/test/kotlin/net/badgersmc/em/application/ShopSignRendererTest.kt src/main/kotlin/net/badgersmc/em/infrastructure/listeners/SignPlaceListener.kt
git commit -m "refactor(shop): extract ShopSignRenderer, reuse in SignPlaceListener (SP5)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 4: ShopManagementService.adminDelete

**Files:**
- Modify: `src/main/kotlin/net/badgersmc/em/application/ShopManagementService.kt`
- Test: `src/test/kotlin/net/badgersmc/em/application/ShopManagementAdminDeleteTest.kt`

- [ ] **Step 1: Write the failing test**

`src/test/kotlin/net/badgersmc/em/application/ShopManagementAdminDeleteTest.kt`:

```kotlin
package net.badgersmc.em.application

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.badgersmc.em.domain.shop.Shop
import net.badgersmc.em.domain.shop.ShopRepository
import net.badgersmc.em.domain.shop.SignDirection
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShopManagementAdminDeleteTest {

    private fun shop(id: Long, owner: UUID) = Shop(
        id = id, stallId = "s", owner = owner,
        signWorld = "world", signX = 1, signY = 2, signZ = 3,
        containerWorld = "world", containerX = 1, containerY = 1, containerZ = 1,
        sellItem = "s", sellAmount = 1, costItem = "c", costAmount = 10,
        direction = SignDirection.SELL,
    )

    @Test fun `deletes a shop owned by someone else`() {
        val repo = mockk<ShopRepository>(relaxed = true)
        val someoneElse = UUID.randomUUID()
        every { repo.findById(42) } returns shop(42, someoneElse)
        val svc = ShopManagementService(repo)

        assertTrue(svc.adminDelete(42))
        verify { repo.delete(42) }
    }

    @Test fun `returns false for a missing shop`() {
        val repo = mockk<ShopRepository>(relaxed = true)
        every { repo.findById(99) } returns null
        assertFalse(ShopManagementService(repo).adminDelete(99))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew test --tests "net.badgersmc.em.application.ShopManagementAdminDeleteTest" -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: FAIL — `adminDelete` unresolved.

- [ ] **Step 3: Add adminDelete**

In `ShopManagementService.kt`, after `delete(...)`:

```kotlin
    /** Delete a shop regardless of owner (admin tooling, SP5). Returns true when deleted. */
    fun adminDelete(shopId: Long): Boolean {
        val shop = shopRepository.findById(shopId) ?: return false
        shopRepository.delete(shopId)
        fireShopDeleted(shop.owner)
        return true
    }
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew test --tests "net.badgersmc.em.application.ShopManagementAdminDeleteTest" -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
cd /d/BadgersMC-Dev/EnthusiaMarket && git add src/main/kotlin/net/badgersmc/em/application/ShopManagementService.kt src/test/kotlin/net/badgersmc/em/application/ShopManagementAdminDeleteTest.kt
git commit -m "feat(admin): ShopManagementService.adminDelete owner-bypass delete (SP5)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 5: breakothers wiring + ShopEditMenu admin widening

**Files:**
- Modify: `src/main/kotlin/net/badgersmc/em/infrastructure/listeners/BlockProtectionListener.kt`
- Modify: `src/main/kotlin/net/badgersmc/em/interaction/gui/ShopEditMenu.kt`
- Modify: `src/main/resources/lang/en_US.yml`

- [ ] **Step 1: Inject AdminBreakMode + add the breakothers branch**

Read `BlockProtectionListener.kt` first. Add `private val adminBreak: net.badgersmc.em.application.AdminBreakMode,` to the constructor. In `onBlockBreak`, inside the `if (shop != null)` block, **between** the existing owner+breakDelete `if` (which `return`s) and the `cancelSignBreak(...)` call, insert:

```kotlin
                if (player.hasPermission("enthusiamarket.admin.shop") && adminBreak.isActive(player.uniqueId)) {
                    management.adminDelete(shop.id)
                    player.sendMessage(lang.msg("shop.admin.breakothers.deleted"))
                    return // allow break; shop already deleted
                }
```

- [ ] **Step 2: Widen the ShopEditMenu admin guard**

In `ShopEditMenu.kt` `open(player)`, change the guard to also accept the shop-admin node:

```kotlin
        if (player.uniqueId != shop.owner &&
            !player.hasPermission("enthusiamarket.admin") &&
            !player.hasPermission("enthusiamarket.admin.shop")
        ) {
            player.sendMessage(lang.msg("shop.edit.not_owner"))
            return
        }
```

- [ ] **Step 3: Add the breakothers lang key (partial shop.admin block)**

In `en_US.yml`, under the top-level `shop:` block (e.g. after the `cmd:` block), add the start of a `shop.admin` block — the rest is added in Task 6. For now add:

```yaml
  admin:
    no_target: "<red>You're not looking at a shop sign or container."
    breakothers:
      deleted: "<green>Admin-broke and deleted that shop."
```

(Use exactly two-space indentation so these nest under `shop:`. `<token>` style, never `{token}`.)

- [ ] **Step 4: Build**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew compileKotlin compileTestKotlin -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: BUILD SUCCESSFUL. (If a `BlockProtectionListener` test constructs it directly, add an `AdminBreakMode()` arg.)

- [ ] **Step 5: Commit**

```bash
cd /d/BadgersMC-Dev/EnthusiaMarket && git add src/main/kotlin/net/badgersmc/em/infrastructure/listeners/BlockProtectionListener.kt src/main/kotlin/net/badgersmc/em/interaction/gui/ShopEditMenu.kt src/main/resources/lang/en_US.yml
git commit -m "feat(admin): breakothers sign-break delete + admin.shop edit access (SP5)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 6: `/shop admin` subcommands + lang

**Files:**
- Modify: `src/main/kotlin/net/badgersmc/em/infrastructure/commands/ShopCommands.kt`
- Modify: `src/main/resources/lang/en_US.yml`

- [ ] **Step 1: Inject the new dependencies into ShopCommands**

Add to the `ShopCommands` constructor (after the existing params):

```kotlin
    private val lookAt: net.badgersmc.em.application.LookAtShopResolver,
    private val adminBreak: net.badgersmc.em.application.AdminBreakMode,
    private val signRenderer: net.badgersmc.em.application.ShopSignRenderer,
```

- [ ] **Step 2: Add a private raycast helper + the five subcommands**

Inside `ShopCommands`, add:

```kotlin
    private fun lookAtShop(player: Player): net.badgersmc.em.domain.shop.Shop? {
        val b = player.getTargetBlockExact(6) ?: return null
        return lookAt.resolve(b.world.name, b.x, b.y, b.z)
    }

    @Subcommand("admin view")
    @Permission("enthusiamarket.admin.shop")
    fun adminView(@Context sender: CommandSender) {
        val player = sender as? Player ?: run { sender.sendMessage(lang.msg("shop.cmd.players_only")); return }
        val shop = lookAtShop(player) ?: run { player.sendMessage(lang.msg("shop.admin.no_target")); return }
        net.badgersmc.em.interaction.gui.ShopEditMenu(shop, shopRepository, management, lang).open(player)
    }

    @Subcommand("admin info")
    @Permission("enthusiamarket.admin.shop")
    fun adminInfo(@Context sender: CommandSender) {
        val player = sender as? Player ?: run { sender.sendMessage(lang.msg("shop.cmd.players_only")); return }
        val shop = lookAtShop(player) ?: run { player.sendMessage(lang.msg("shop.admin.no_target")); return }
        val owner = org.bukkit.Bukkit.getOfflinePlayer(shop.owner).name ?: "Unknown"
        val sell = ItemStackSerializer.deserialize(shop.sellItem)?.type?.name?.lowercase() ?: "?"
        player.sendMessage(lang.msg("shop.admin.info.header", "owner" to owner))
        player.sendMessage(lang.msg("shop.admin.info.where",
            "world" to shop.signWorld, "x" to shop.signX, "y" to shop.signY, "z" to shop.signZ,
            "cworld" to shop.containerWorld, "cx" to shop.containerX, "cy" to shop.containerY, "cz" to shop.containerZ))
        player.sendMessage(lang.msg("shop.admin.info.trade",
            "dir" to shop.direction.name, "sell_amt" to shop.sellAmount, "sell" to sell, "cost" to shop.costAmount))
        player.sendMessage(lang.msg("shop.admin.info.flags",
            "trusted" to shop.trusted.size, "frozen" to shop.frozen, "searchable" to shop.searchEnabled))
    }

    @Subcommand("admin remove")
    @Permission("enthusiamarket.admin.shop")
    fun adminRemove(@Context sender: CommandSender) {
        val player = sender as? Player ?: run { sender.sendMessage(lang.msg("shop.cmd.players_only")); return }
        val shop = lookAtShop(player) ?: run { player.sendMessage(lang.msg("shop.admin.no_target")); return }
        if (management.adminDelete(shop.id)) player.sendMessage(lang.msg("shop.admin.remove.done"))
        else player.sendMessage(lang.msg("shop.admin.remove.not_found"))
    }

    @Subcommand("admin fix")
    @Permission("enthusiamarket.admin.shop")
    fun adminFix(@Context sender: CommandSender) {
        val player = sender as? Player ?: run { sender.sendMessage(lang.msg("shop.cmd.players_only")); return }
        val shop = lookAtShop(player) ?: run { player.sendMessage(lang.msg("shop.admin.no_target")); return }
        val signState = org.bukkit.Bukkit.getWorld(shop.signWorld)
            ?.getBlockAt(shop.signX, shop.signY, shop.signZ)?.state
        if (signState !is org.bukkit.block.Sign) { player.sendMessage(lang.msg("shop.admin.fix.not_a_sign")); return }
        val sell = ItemStackSerializer.deserialize(shop.sellItem)?.type?.name?.lowercase() ?: "?"
        val lines = signRenderer.lines(shop.direction, sell, shop.sellAmount, shop.costAmount.toLong())
        val side = signState.getSide(org.bukkit.block.sign.Side.FRONT)
        lines.forEachIndexed { i, c -> side.line(i, c) }
        signState.update()
        val containerState = org.bukkit.Bukkit.getWorld(shop.containerWorld)
            ?.getBlockAt(shop.containerX, shop.containerY, shop.containerZ)?.state
        if (containerState !is org.bukkit.block.Container) player.sendMessage(lang.msg("shop.admin.fix.container_missing"))
        else player.sendMessage(lang.msg("shop.admin.fix.done"))
    }

    @Subcommand("admin breakothers")
    @Permission("enthusiamarket.admin.shop")
    fun adminBreakOthers(
        @Context sender: CommandSender,
        @net.badgersmc.nexus.commands.annotations.Arg("mode") mode: String = "on",
    ) {
        val player = sender as? Player ?: run { sender.sendMessage(lang.msg("shop.cmd.players_only")); return }
        val durationMs = BreakDeleteMode.parseDurationMs(mode)
        if (durationMs == null) {
            adminBreak.disable(player.uniqueId)
            player.sendMessage(lang.msg("shop.admin.breakothers.disabled"))
            return
        }
        adminBreak.enable(player.uniqueId, durationMs)
        player.sendMessage(lang.msg("shop.admin.breakothers.enabled", "minutes" to (durationMs / 60_000)))
    }
```

(`ItemStackSerializer` and `BreakDeleteMode` are already imported in `ShopCommands.kt` from SP1/SP2; if not, add the imports.)

- [ ] **Step 3: Complete the shop.admin lang block**

In `en_US.yml`, extend the `shop.admin` block started in Task 5 so the full block reads:

```yaml
  admin:
    no_target: "<red>You're not looking at a shop sign or container."
    info:
      header: "<gold>━━ Shop owned by <yellow><owner></yellow> ━━"
      where: "<gray>Sign <white><world> <x>,<y>,<z></white>  <dark_gray>|  <gray>Chest <white><cworld> <cx>,<cy>,<cz>"
      trade: "<gray>Mode <white><dir></white>  <dark_gray>|  <green><sell_amt>x <sell></green>  <dark_gray>|  <gold><cost>"
      flags: "<gray>Trusted <white><trusted></white>  <dark_gray>|  <gray>Frozen <white><frozen></white>  <dark_gray>|  <gray>Searchable <white><searchable>"
    remove:
      done: "<green>Shop removed."
      not_found: "<red>That shop no longer exists."
    fix:
      done: "<green>Sign re-rendered; container OK."
      container_missing: "<yellow>Sign re-rendered, but the linked container is gone — use /shop admin remove."
      not_a_sign: "<red>The stored sign block isn't a sign anymore — use /shop admin remove."
    breakothers:
      enabled: "<green>Break-others mode ON for <gold><minutes></gold> minute(s). Break any shop sign to delete it."
      disabled: "<gray>Break-others mode disabled."
      deleted: "<green>Admin-broke and deleted that shop."
```

> Note: `enabled`/`disabled` (not `on`/`off`) — SnakeYAML parses unquoted `on:`/`off:` keys as booleans, which would break the lookup.

(Replace the partial block from Task 5 with this complete one. Keep two-space indentation under `shop:`.)

- [ ] **Step 4: Build (+ fix ShopCommands test constructor if present)**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew compileKotlin compileTestKotlin -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: BUILD SUCCESSFUL. If a `ShopCommands` test constructs it directly, add `mockk<LookAtShopResolver>(relaxed = true)`, `AdminBreakMode()`, `ShopSignRenderer()` args.

- [ ] **Step 5: Commit**

```bash
cd /d/BadgersMC-Dev/EnthusiaMarket && git add src/main/kotlin/net/badgersmc/em/infrastructure/commands/ShopCommands.kt src/main/resources/lang/en_US.yml
git commit -m "feat(admin): /shop admin view/info/remove/fix/breakothers (SP5)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 7: SearchResultsMenu admin teleport

**Files:**
- Modify: `src/main/kotlin/net/badgersmc/em/interaction/gui/SearchResultsMenu.kt`
- Modify: `src/main/resources/lang/en_US.yml`

- [ ] **Step 1: Add the teleport branch to the result click**

In `SearchResultsMenu.kt`, replace the result-icon click lambda body (currently closes inventory + sends `gui.shop.search.clicked`) with:

```kotlin
            pane.addItem(GuiItem(icon) {
                it.isCancelled = true
                player.closeInventory()
                if (player.hasPermission("enthusiamarket.admin.shop")) {
                    val world = org.bukkit.Bukkit.getWorld(shop.signWorld)
                    if (world != null) {
                        player.teleport(org.bukkit.Location(
                            world, shop.signX + 0.5, shop.signY.toDouble(), shop.signZ + 0.5,
                            player.location.yaw, player.location.pitch,
                        ))
                        player.sendMessage(lang.msg("gui.shop.search.teleported",
                            "world" to shop.signWorld, "x" to shop.signX, "y" to shop.signY, "z" to shop.signZ))
                    }
                } else {
                    player.sendMessage(lang.msg("gui.shop.search.clicked",
                        "world" to shop.signWorld, "x" to shop.signX, "y" to shop.signY, "z" to shop.signZ))
                }
            }, idx % 9, idx / 9)
```

- [ ] **Step 2: Add the lang key**

In `en_US.yml`, under `gui.shop.search`, beside `clicked`, add:

```yaml
      teleported: "<green>Teleported to shop at <white><world> <x> <y> <z></white>."
```

- [ ] **Step 3: Build**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew compileKotlin -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
cd /d/BadgersMC-Dev/EnthusiaMarket && git add src/main/kotlin/net/badgersmc/em/interaction/gui/SearchResultsMenu.kt src/main/resources/lang/en_US.yml
git commit -m "feat(admin): admin teleport from /shop search results (SP5)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 8: Final gate

- [ ] **Step 1: Full verification on the committed HEAD**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew clean detekt test shadowJar -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: BUILD SUCCESSFUL, detekt 0, all tests pass.

- [ ] **Step 2: Mark progress + commit**

Append to `docs/tasks.md`: `- [x] ItemShops parity SP5 — admin tooling (/shop admin view/info/remove/fix/breakothers + search teleport)`. Then:

```bash
cd /d/BadgersMC-Dev/EnthusiaMarket && git add docs/tasks.md
git commit -m "docs: mark ItemShops parity SP5 (admin tooling) complete

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

- [ ] **Step 3: Report**

Report the final gate output + commit list. Do NOT push.

---

## Self-Review Notes (for the implementer)

1. **`AdminBreakMode` mirrors `BreakDeleteMode` exactly** (Task 1) — same `ConcurrentHashMap<UUID, Long>` shape. Reuse `BreakDeleteMode.parseDurationMs` (Task 6); do not add a second parser.
2. **`LookAtShopResolver` is coord-based, not Block-based** (Task 2) — the caller (`ShopCommands.lookAtShop`) does the `getTargetBlockExact` raycast and passes `world.name, x, y, z`. This keeps the resolver pure (MockK repo, no MockBukkit).
3. **`ShopSignRenderer` is Bukkit-free** (Task 3) — it takes a material-name string, not an ItemStack. The caller deserializes. SignPlaceListener passes `held.type.name.lowercase()`; fix passes the deserialized sell type.
4. **Don't break the SignPlaceListener refactor** (Task 3 Step 5) — it must stay behaviour-identical; only the four `event.line` formats move into the renderer. Read the file before editing; remove now-unused imports (`NamedTextColor`, possibly `AdventureComponent`) or detekt will flag them.
5. **breakothers branch ordering** (Task 5 Step 1) — it goes AFTER the owner+breakDelete check and BEFORE `cancelSignBreak`, so owners keep their own breakdelete behaviour and only admins-with-mode get the any-shop delete.
6. **Multi-segment subcommands** (Task 6) — `@Subcommand("admin view")` etc. work the same way `AdminCommands`' `auction start` / `stall members add` do (PaperCommandRegistry builds literals bottom-up). The `breakothers` `@Arg("mode")` mirrors SP1's `breakdelete`.
7. **Lang indentation** (Tasks 5–7) — the `shop.admin` block nests under the existing top-level `shop:` at two spaces; `gui.shop.search.teleported` nests under `gui.shop.search`. Watch for accidental duplicate `admin:` keys (there is already a TOP-LEVEL `admin:` block for `/em` messages — the new one is `shop.admin`, a different parent). Do not merge them.
8. **Constructor churn** (Tasks 3, 5, 6) — `SignPlaceListener`, `BlockProtectionListener`, and `ShopCommands` each gain constructor params. If any has a direct-construction test, add the new args (`ShopSignRenderer()`, `AdminBreakMode()`, `mockk(relaxed=true)` resolver). Nexus injects them in production automatically.
