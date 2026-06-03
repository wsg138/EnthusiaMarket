# Shop Management Commands Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a player-facing `/shop` command (ItemShops parity) with `list`, `edit`, `trust`/`untrust`, `delete`, and `breakdelete` verbs over EM's existing money-model shops.

**Architecture:** Hexagonal/SPEAR. Pure application services (`ShopManagementService`, `BreakDeleteMode`) hold the logic and are unit-tested with mockk; a thin `@Command` class and IFramework GUIs (mirroring the existing `TrustManageMenu`) form the infrastructure layer. No DB migration — `Shop.trusted` is already persisted and `ShopRepository` already has `findByOwner`/`delete`/`upsert`.

**Tech Stack:** Kotlin 2.0.0, Nexus DI + commands, IFramework (`com.github.stefvanschie.inventoryframework`), JUnit 5 + MockK + MockBukkit, detekt 1.23.8.

**Reference spec:** `docs/superpowers/specs/2026-06-03-itemshops-parity-shop-management-design.md`

**Standing rules (every task):**
- Bash cwd resets — prefix every command with `cd /d/BadgersMC-Dev/EnthusiaMarket &&` (on Hermes' box substitute `/opt/data/EnthusiaMarket`).
- Every gradle command includes `-Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain` (substitute `/opt/data/...` on Hermes' box).
- LF→CRLF git warnings are expected — ignore.
- Branch: `feat/shop-management-commands`. Do not push unless told. Commit after every task with the given message.
- TDD: write the failing test, run it RED first, then implement GREEN. Never weaken a test to pass.
- Gate rule: after each `Run:` step compare to `Expected:`. Mismatch → STOP, fix the one thing, re-run; HALT after 3 failed attempts.

---

## CONFIRMED API SYMBOLS (verified against the repo — use exactly, do not re-derive)

**`Shop` data class** (`net.badgersmc.em.domain.shop.Shop`) — fields used here:
`id: Long`, `stallId: String`, `owner: UUID`, `signWorld/signX/signY/signZ`,
`containerWorld/containerX/containerY/containerZ`, `sellItem: String` (base64),
`sellAmount: Int`, `costItem: String` (base64), `costAmount: Int`, `trusted: Set<UUID>`,
`hopperAllowIn: Boolean`, `hopperAllowOut: Boolean`, `frozen: Boolean`,
`direction: SignDirection`. `init` requires `sellAmount > 0`, `costAmount > 0`, `stallId` non-blank.
Mutate with `.copy(...)`.

**`ShopRepository`** (`net.badgersmc.em.domain.shop.ShopRepository`):
`findByOwner(owner: UUID): List<Shop>`, `findById(id: Long): Shop?`, `upsert(shop: Shop): Shop`,
`delete(id: Long)`, `findBySign(world, x, y, z): Shop?`.

**`ItemStackSerializer`** (`net.badgersmc.em.application.ItemStackSerializer`):
`serialize(item: ItemStack): String`, `deserialize(base64: String): ItemStack?`.

**Nexus command annotations** (exact imports):
- `net.badgersmc.nexus.commands.annotations.Command` — `@Command(name = "shop", description = "...", aliases = ["shops"])` on the class.
- `net.badgersmc.nexus.commands.annotations.Subcommand` is `net.badgersmc.nexus.paper.commands.annotations.Subcommand` — `@Subcommand("list")` on methods.
- `net.badgersmc.nexus.paper.commands.annotations.Permission` — `@Permission("enthusiamarket.shop.use")`.
- `net.badgersmc.nexus.commands.annotations.Context` — `fun x(@Context sender: CommandSender)`.
- `net.badgersmc.nexus.commands.annotations.Arg` — `@Arg("player") name: String`. Optional args use Kotlin defaults (`@Arg("mode") mode: String = "menu"`).
- A `@Command` class with constructor-injected `@Service`/`@Component` deps is auto-discovered by `ctx.registerPaperCommands` — NO manual registration needed.

**IFramework GUI** (mirror `interaction/gui/TrustManageMenu.kt` + `PurchaseMenu.kt`):
- `com.github.stefvanschie.inventoryframework.gui.type.ChestGui(rows: Int, ComponentHolder.of(component))`
- `com.github.stefvanschie.inventoryframework.adventuresupport.ComponentHolder` — `ComponentHolder.of(lang.msg("key"))`
- `com.github.stefvanschie.inventoryframework.pane.StaticPane(length, height)` — `StaticPane(9, rows)`
- `com.github.stefvanschie.inventoryframework.gui.GuiItem(itemStack) { event -> ... }` — click consumer; call `event.isCancelled = true` inside.
- `pane.addItem(guiItem, x, y)`; `gui.addPane(pane)`; `gui.show(player)`.

**`Menu`** (`net.badgersmc.em.interaction.Menu`): `interface Menu { fun open(player: Player) }`.

**`LangService`** (`net.badgersmc.nexus.i18n.LangService`): `lang.msg("key", "tok" to value, ...)` returns
an Adventure `Component`; placeholder syntax in lang files is `<token>` (NEVER `{token}`).
`lang.legacy("key", ...)` returns a legacy String when needed.

**Existing `ShopEditMenu`** ctor today: `ShopEditMenu(shop: Shop, shopRepository: ShopRepository, lang: LangService)`.

**`BlockProtectionListener`** (`infrastructure/listeners/BlockProtectionListener.kt`): already resolves a
shop at a broken block via `shopRepository.findBySign(...)` and checks `shop.trusted.contains(player.uniqueId)`.

---

## Task 1: ShopManagementService (pure logic)

**Files:**
- Create: `src/main/kotlin/net/badgersmc/em/application/ShopManagementService.kt`
- Test: `src/test/kotlin/net/badgersmc/em/application/ShopManagementServiceTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package net.badgersmc.em.application

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import net.badgersmc.em.domain.shop.Shop
import net.badgersmc.em.domain.shop.ShopRepository
import net.badgersmc.em.domain.shop.SignDirection
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShopManagementServiceTest {

    private val owner = UUID.randomUUID()
    private val target = UUID.randomUUID()

    private fun shop(id: Long, owner: UUID = this.owner, trusted: Set<UUID> = emptySet()) = Shop(
        id = id, stallId = "stall1", owner = owner,
        signWorld = "world", signX = 1, signY = 2, signZ = 3,
        containerWorld = "world", containerX = 1, containerY = 1, containerZ = 1,
        sellItem = "s", sellAmount = 1, costItem = "c", costAmount = 10,
        trusted = trusted, direction = SignDirection.SELL,
    )

    @Test fun `shopsOwnedBy delegates to repository`() {
        val repo = mockk<ShopRepository>()
        every { repo.findByOwner(owner) } returns listOf(shop(1), shop(2))
        val svc = ShopManagementService(repo)
        assertEquals(2, svc.shopsOwnedBy(owner).size)
    }

    @Test fun `trust adds target to listed shops owned by owner and persists`() {
        val repo = mockk<ShopRepository>(relaxed = true)
        every { repo.findById(1) } returns shop(1)
        every { repo.findById(2) } returns shop(2)
        val svc = ShopManagementService(repo)
        val saved = mutableListOf<Shop>()
        every { repo.upsert(capture(saved)) } answers { firstArg() }

        val changed = svc.trust(owner, target, listOf(1L, 2L))
        assertEquals(2, changed)
        assertTrue(saved.all { target in it.trusted })
    }

    @Test fun `trust skips shops not owned by the actor`() {
        val repo = mockk<ShopRepository>(relaxed = true)
        every { repo.findById(9) } returns shop(9, owner = UUID.randomUUID())
        val svc = ShopManagementService(repo)
        assertEquals(0, svc.trust(owner, target, listOf(9L)))
        verify(exactly = 0) { repo.upsert(any()) }
    }

    @Test fun `untrust removes target and persists`() {
        val repo = mockk<ShopRepository>(relaxed = true)
        every { repo.findById(1) } returns shop(1, trusted = setOf(target))
        val svc = ShopManagementService(repo)
        val saved = slot<Shop>()
        every { repo.upsert(capture(saved)) } answers { firstArg() }
        assertEquals(1, svc.untrust(owner, target, listOf(1L)))
        assertFalse(target in saved.captured.trusted)
    }

    @Test fun `delete enforces ownership`() {
        val repo = mockk<ShopRepository>(relaxed = true)
        every { repo.findById(1) } returns shop(1)
        every { repo.findById(2) } returns shop(2, owner = UUID.randomUUID())
        val svc = ShopManagementService(repo)
        assertTrue(svc.delete(owner, 1))
        assertFalse(svc.delete(owner, 2))
        verify(exactly = 1) { repo.delete(1) }
        verify(exactly = 0) { repo.delete(2) }
    }

    @Test fun `deleteAll removes every owned shop and counts`() {
        val repo = mockk<ShopRepository>(relaxed = true)
        every { repo.findByOwner(owner) } returns listOf(shop(1), shop(2), shop(3))
        val svc = ShopManagementService(repo)
        assertEquals(3, svc.deleteAll(owner))
        verify { repo.delete(1) }
        verify { repo.delete(2) }
        verify { repo.delete(3) }
    }

    @Test fun `trustAll trusts target on every owned shop`() {
        val repo = mockk<ShopRepository>(relaxed = true)
        every { repo.findByOwner(owner) } returns listOf(shop(1), shop(2))
        every { repo.findById(1) } returns shop(1)
        every { repo.findById(2) } returns shop(2)
        every { repo.upsert(any()) } answers { firstArg() }
        val svc = ShopManagementService(repo)
        assertEquals(2, svc.trustAll(owner, target))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew test --tests "net.badgersmc.em.application.ShopManagementServiceTest" -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: FAIL — `ShopManagementService` not defined.

- [ ] **Step 3: Implement ShopManagementService**

```kotlin
package net.badgersmc.em.application

import net.badgersmc.em.domain.shop.Shop
import net.badgersmc.em.domain.shop.ShopRepository
import net.badgersmc.nexus.annotations.Service
import java.util.UUID

/**
 * Player-facing shop management operations (ItemShops parity sub-project 1):
 * list / trust / untrust / delete over the owner's shops. All mutations are
 * ownership-checked against the actor; menus are a convenience layer on top.
 */
@Service
class ShopManagementService(
    private val shopRepository: ShopRepository,
) {
    fun shopsOwnedBy(owner: UUID): List<Shop> = shopRepository.findByOwner(owner)

    /** Trust [target] on each of [shopIds] the [actor] actually owns. Returns count changed. */
    fun trust(actor: UUID, target: UUID, shopIds: List<Long>): Int =
        mutateOwned(actor, shopIds) { it.copy(trusted = it.trusted + target) }

    /** Untrust [target] on each of [shopIds] the [actor] owns. Returns count changed. */
    fun untrust(actor: UUID, target: UUID, shopIds: List<Long>): Int =
        mutateOwned(actor, shopIds) { it.copy(trusted = it.trusted - target) }

    fun trustAll(actor: UUID, target: UUID): Int =
        trust(actor, target, shopsOwnedBy(actor).map { it.id })

    fun untrustAll(actor: UUID, target: UUID): Int =
        untrust(actor, target, shopsOwnedBy(actor).map { it.id })

    /** Delete a single shop if [actor] owns it. Returns true when deleted. */
    fun delete(actor: UUID, shopId: Long): Boolean {
        val shop = shopRepository.findById(shopId) ?: return false
        if (shop.owner != actor) return false
        shopRepository.delete(shopId)
        return true
    }

    /** Delete every shop [actor] owns. Returns count deleted. */
    fun deleteAll(actor: UUID): Int {
        val owned = shopsOwnedBy(actor)
        owned.forEach { shopRepository.delete(it.id) }
        return owned.size
    }

    private fun mutateOwned(actor: UUID, shopIds: List<Long>, edit: (Shop) -> Shop): Int {
        var changed = 0
        for (id in shopIds) {
            val shop = shopRepository.findById(id) ?: continue
            if (shop.owner != actor) continue
            val updated = edit(shop)
            if (updated != shop) {
                shopRepository.upsert(updated)
                changed++
            }
        }
        return changed
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew test --tests "net.badgersmc.em.application.ShopManagementServiceTest" -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
cd /d/BadgersMC-Dev/EnthusiaMarket && git add src/main/kotlin/net/badgersmc/em/application/ShopManagementService.kt src/test/kotlin/net/badgersmc/em/application/ShopManagementServiceTest.kt
git commit -m "feat(shop): ShopManagementService — list/trust/untrust/delete

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 2: BreakDeleteMode (timed per-player toggle)

**Files:**
- Create: `src/main/kotlin/net/badgersmc/em/application/BreakDeleteMode.kt`
- Test: `src/test/kotlin/net/badgersmc/em/application/BreakDeleteModeTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
package net.badgersmc.em.application

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BreakDeleteModeTest {

    @Test fun `enable makes it active within the window`() {
        val mode = BreakDeleteMode()
        val p = UUID.randomUUID()
        mode.enable(p, durationMs = 1000, nowMs = 0)
        assertTrue(mode.isActive(p, nowMs = 500))
    }

    @Test fun `expires after the window and purges`() {
        val mode = BreakDeleteMode()
        val p = UUID.randomUUID()
        mode.enable(p, durationMs = 1000, nowMs = 0)
        assertFalse(mode.isActive(p, nowMs = 1001))
        // second read confirms purge did not throw / re-activate
        assertFalse(mode.isActive(p, nowMs = 2000))
    }

    @Test fun `disable turns it off`() {
        val mode = BreakDeleteMode()
        val p = UUID.randomUUID()
        mode.enable(p, durationMs = 10000, nowMs = 0)
        mode.disable(p)
        assertFalse(mode.isActive(p, nowMs = 1))
    }

    @Test fun `per-player isolation`() {
        val mode = BreakDeleteMode()
        val a = UUID.randomUUID(); val b = UUID.randomUUID()
        mode.enable(a, durationMs = 1000, nowMs = 0)
        assertTrue(mode.isActive(a, nowMs = 1))
        assertFalse(mode.isActive(b, nowMs = 1))
    }

    @Test fun `parseDuration handles off on 5m and garbage`() {
        assertTrue(BreakDeleteMode.parseDurationMs("off") == null)
        assertTrue(BreakDeleteMode.parseDurationMs("on") == 5L * 60_000)
        assertTrue(BreakDeleteMode.parseDurationMs("5m") == 5L * 60_000)
        assertTrue(BreakDeleteMode.parseDurationMs("10m") == 10L * 60_000)
        assertTrue(BreakDeleteMode.parseDurationMs("garbage") == 5L * 60_000)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew test --tests "net.badgersmc.em.application.BreakDeleteModeTest" -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: FAIL — `BreakDeleteMode` not defined.

- [ ] **Step 3: Implement BreakDeleteMode**

```kotlin
package net.badgersmc.em.application

import net.badgersmc.nexus.annotations.Component
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Ephemeral per-player "break to delete" mode (ItemShops parity). While active,
 * the BlockProtectionListener lets the owner break their own shop sign and
 * deletes the shop. Not persisted — clears on restart, exactly like ItemShops.
 */
@Component
class BreakDeleteMode {

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

    companion object {
        /** Parse the ItemShops-style arg: null=off, "on"/absent=5m, "Nm"=N minutes, garbage=5m. */
        fun parseDurationMs(arg: String?): Long? {
            val a = arg?.lowercase()?.trim()
            if (a == "off") return null
            if (a == null || a == "on" || a.isEmpty()) return DEFAULT_MS
            if (a.endsWith("m")) {
                val mins = a.dropLast(1).toLongOrNull()
                if (mins != null && mins > 0) return mins * 60_000
            }
            return DEFAULT_MS
        }

        private const val DEFAULT_MS = 5L * 60_000
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew test --tests "net.badgersmc.em.application.BreakDeleteModeTest" -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
cd /d/BadgersMC-Dev/EnthusiaMarket && git add src/main/kotlin/net/badgersmc/em/application/BreakDeleteMode.kt src/test/kotlin/net/badgersmc/em/application/BreakDeleteModeTest.kt
git commit -m "feat(shop): BreakDeleteMode ephemeral timed toggle + arg parser

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 3: ShopEditMenu restructure (pure applyEdits helper + menu)

**Files:**
- Modify: `src/main/kotlin/net/badgersmc/em/interaction/gui/ShopEditMenu.kt`
- Test: `src/test/kotlin/net/badgersmc/em/interaction/gui/ShopEditMenuApplyTest.kt`

- [ ] **Step 1: Write the failing test for the pure edit helper**

```kotlin
package net.badgersmc.em.interaction.gui

import net.badgersmc.em.domain.shop.Shop
import net.badgersmc.em.domain.shop.SignDirection
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class ShopEditMenuApplyTest {

    private fun shop() = Shop(
        id = 1, stallId = "stall1", owner = UUID.randomUUID(),
        signWorld = "world", signX = 1, signY = 2, signZ = 3,
        containerWorld = "world", containerX = 1, containerY = 1, containerZ = 1,
        sellItem = "old", sellAmount = 1, costItem = "c", costAmount = 10,
        hopperAllowIn = true, hopperAllowOut = true, frozen = false,
        direction = SignDirection.SELL,
    )

    @Test fun `applyEdits returns a copy with the edited fields`() {
        val updated = ShopEditMenu.applyEdits(
            shop(), sellItemB64 = "new", sellAmount = 5, costAmount = 99,
            hopperIn = false, hopperOut = false, frozen = true,
        )
        assertEquals("new", updated.sellItem)
        assertEquals(5, updated.sellAmount)
        assertEquals(99, updated.costAmount)
        assertEquals(false, updated.hopperAllowIn)
        assertEquals(false, updated.hopperAllowOut)
        assertEquals(true, updated.frozen)
    }

    @Test fun `applyEdits clamps amounts to at least one`() {
        val updated = ShopEditMenu.applyEdits(
            shop(), sellItemB64 = "x", sellAmount = 0, costAmount = -5,
            hopperIn = true, hopperOut = true, frozen = false,
        )
        assertEquals(1, updated.sellAmount)
        assertEquals(1, updated.costAmount)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew test --tests "net.badgersmc.em.interaction.gui.ShopEditMenuApplyTest" -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: FAIL — `applyEdits` not defined.

- [ ] **Step 3: Restructure ShopEditMenu**

Replace the contents of `ShopEditMenu.kt` with the version below. It keeps the `Menu` interface,
adds the pure `applyEdits` companion helper, and rebuilds the GUI to ItemShops' layout: a sell-item
slot, sell-amount +/- controls, cost (money) +/- controls, hopper in/out toggles, a freeze toggle,
and a delete button. Trust is removed (now `/shop trust`). The menu re-renders on each control
click (same pattern as `CreateShopMenu`). The constructor gains `ShopManagementService` for delete.

```kotlin
package net.badgersmc.em.interaction.gui

import com.github.stefvanschie.inventoryframework.adventuresupport.ComponentHolder
import com.github.stefvanschie.inventoryframework.gui.GuiItem
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui
import com.github.stefvanschie.inventoryframework.pane.StaticPane
import net.badgersmc.em.application.ItemStackSerializer
import net.badgersmc.em.application.ShopManagementService
import net.badgersmc.em.domain.shop.Shop
import net.badgersmc.em.domain.shop.ShopRepository
import net.badgersmc.nexus.i18n.LangService
import net.badgersmc.em.interaction.Menu
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * Owner edit GUI for a sign shop (ItemShops parity sub-project 1). Edits the sell
 * item + amount, the cost (money) amount, hopper in/out, freeze, and delete. Trust
 * is managed via `/shop trust`. Under EM's current money model the cost is a number;
 * sub-project 3 (barter) adds an item-cost mode here.
 */
class ShopEditMenu(
    private val shop: Shop,
    private val shopRepository: ShopRepository,
    private val management: ShopManagementService,
    private val lang: LangService,
) : Menu {

    private var sellItemB64: String = shop.sellItem
    private var sellAmount: Int = shop.sellAmount
    private var costAmount: Int = shop.costAmount
    private var hopperIn: Boolean = shop.hopperAllowIn
    private var hopperOut: Boolean = shop.hopperAllowOut
    private var frozen: Boolean = shop.frozen

    override fun open(player: Player) {
        if (player.uniqueId != shop.owner && !player.hasPermission("enthusiamarket.admin")) {
            player.sendMessage(lang.msg("shop.edit.not_owner"))
            return
        }
        render(player)
    }

    private fun render(player: Player) {
        val gui = ChestGui(3, ComponentHolder.of(lang.msg("gui.shop.edit.title")))
        val pane = StaticPane(9, 3)

        // Sell item preview (decoded). Clicking sets the sell item to the item in hand.
        val preview = ItemStackSerializer.deserialize(sellItemB64) ?: ItemStack(Material.BARRIER)
        pane.addItem(GuiItem(preview) { event ->
            event.isCancelled = true
            val hand = player.inventory.itemInMainHand
            if (hand.type != Material.AIR && hand.amount > 0) {
                sellItemB64 = ItemStackSerializer.serialize(hand.clone().apply { amount = 1 })
                sellAmount = hand.amount.coerceAtLeast(1)
                render(player)
            }
        }, 1, 1)

        // Sell amount controls.
        pane.addItem(GuiItem(decorated(Material.LIME_DYE, lang.msg("gui.shop.edit.sell_up", "amount" to sellAmount))) {
            it.isCancelled = true; sellAmount += 1; render(player)
        }, 2, 0)
        pane.addItem(GuiItem(decorated(Material.PAPER, lang.msg("gui.shop.edit.sell_amount", "amount" to sellAmount))), 2, 1)
        pane.addItem(GuiItem(decorated(Material.RED_DYE, lang.msg("gui.shop.edit.sell_down", "amount" to sellAmount))) {
            it.isCancelled = true; sellAmount = (sellAmount - 1).coerceAtLeast(1); render(player)
        }, 2, 2)

        // Cost (money) controls.
        pane.addItem(GuiItem(decorated(Material.LIME_DYE, lang.msg("gui.shop.edit.cost_up", "cost" to costAmount))) {
            it.isCancelled = true; costAmount += 10; render(player)
        }, 4, 0)
        pane.addItem(GuiItem(decorated(Material.EMERALD, lang.msg("gui.shop.edit.cost", "cost" to costAmount))), 4, 1)
        pane.addItem(GuiItem(decorated(Material.RED_DYE, lang.msg("gui.shop.edit.cost_down", "cost" to costAmount))) {
            it.isCancelled = true; costAmount = (costAmount - 10).coerceAtLeast(1); render(player)
        }, 4, 2)

        // Hopper toggles + freeze.
        pane.addItem(GuiItem(decorated(if (hopperIn) Material.HOPPER else Material.GRAY_DYE, lang.msg("gui.shop.edit.hopper_in", "state" to hopperIn))) {
            it.isCancelled = true; hopperIn = !hopperIn; render(player)
        }, 6, 0)
        pane.addItem(GuiItem(decorated(if (hopperOut) Material.HOPPER else Material.GRAY_DYE, lang.msg("gui.shop.edit.hopper_out", "state" to hopperOut))) {
            it.isCancelled = true; hopperOut = !hopperOut; render(player)
        }, 6, 1)
        pane.addItem(GuiItem(decorated(if (frozen) Material.BLUE_ICE else Material.WATER_BUCKET, lang.msg("gui.shop.edit.freeze", "state" to frozen))) {
            it.isCancelled = true; frozen = !frozen; render(player)
        }, 6, 2)

        // Save + delete.
        pane.addItem(GuiItem(decorated(Material.LIME_STAINED_GLASS_PANE, lang.msg("gui.shop.edit.save"))) {
            it.isCancelled = true
            shopRepository.upsert(applyEdits(shop, sellItemB64, sellAmount, costAmount, hopperIn, hopperOut, frozen))
            player.closeInventory()
            player.sendMessage(lang.msg("shop.edit.saved"))
        }, 8, 0)
        pane.addItem(GuiItem(decorated(Material.RED_CONCRETE, lang.msg("gui.shop.edit.delete"))) {
            it.isCancelled = true
            management.delete(shop.owner, shop.id)
            player.closeInventory()
            player.sendMessage(lang.msg("shop.delete.done"))
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

    companion object {
        /** Pure: produce the edited Shop copy. Amounts clamp to >= 1 (Shop.init requires it). */
        fun applyEdits(
            shop: Shop, sellItemB64: String, sellAmount: Int, costAmount: Int,
            hopperIn: Boolean, hopperOut: Boolean, frozen: Boolean,
        ): Shop = shop.copy(
            sellItem = sellItemB64,
            sellAmount = sellAmount.coerceAtLeast(1),
            costAmount = costAmount.coerceAtLeast(1),
            hopperAllowIn = hopperIn,
            hopperAllowOut = hopperOut,
            frozen = frozen,
        )
    }
}
```

- [ ] **Step 4: Find and update existing ShopEditMenu constructor call sites**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && grep -rn "ShopEditMenu(" src/main/kotlin src/test/kotlin`
For each call site, add the `management: ShopManagementService` argument (3rd position). The known
site is `OwnedShopsMenu` (created in Task 6) — if any OTHER site exists (e.g. a listener), inject
`ShopManagementService` there and pass it. If a test constructs `ShopEditMenu`, pass a
`mockk<ShopManagementService>(relaxed = true)`.

- [ ] **Step 5: Run the test + compile**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew test --tests "net.badgersmc.em.interaction.gui.ShopEditMenuApplyTest" compileKotlin -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: PASS + BUILD SUCCESSFUL. If old `gui.edit.*` lang keys are now unused, that is fine. If detekt later flags the old keys, ignore (lang keys aren't detekt-scanned).

- [ ] **Step 6: Commit**

```bash
cd /d/BadgersMC-Dev/EnthusiaMarket && git add src/main/kotlin/net/badgersmc/em/interaction/gui/ShopEditMenu.kt src/test/kotlin/net/badgersmc/em/interaction/gui/ShopEditMenuApplyTest.kt
git commit -m "feat(shop): restructure ShopEditMenu to ItemShops layout (sell/amount/cost/hopper/freeze/delete)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 4: ShopCommands skeleton + `/shop list`

**Files:**
- Create: `src/main/kotlin/net/badgersmc/em/infrastructure/commands/ShopCommands.kt`
- Modify: `src/main/resources/lang/en_US.yml`

- [ ] **Step 1: Add lang keys**

In `en_US.yml`, under the top-level `shop:` block (it already exists), add a `cmd:` sub-block.
Use `<token>` placeholders (NEVER `{token}`):

```yaml
  cmd:
    players_only: "<red>Players only."
    none_owned: "<gray>You own no shops."
    list_header: "<gold>Your shops (<count>)"
    list_line: "<gray>- <white><world> <x>,<y>,<z> <dark_gray>| <green><sell_amt>x <sell> <gray>for <gold><cost>"
    unknown_player: "<red>Unknown player: <name>"
    trusted_all: "<green>Trusted <white><name> <green>on all your shops (<count>)."
    untrusted_all: "<yellow>Untrusted <white><name> <yellow>on all your shops (<count>)."
    deleted_all: "<yellow>Deleted <count> shop(s)."
    breakdelete_on: "<green>Break-delete mode enabled for <minutes> minute(s)."
    breakdelete_off: "<yellow>Break-delete mode disabled."
    no_permission: "<red>You don't have permission for that."
```

- [ ] **Step 2: Implement ShopCommands with `list` only**

```kotlin
package net.badgersmc.em.infrastructure.commands

import net.badgersmc.em.application.ItemStackSerializer
import net.badgersmc.em.application.ShopManagementService
import net.badgersmc.nexus.commands.annotations.Command
import net.badgersmc.nexus.commands.annotations.Context
import net.badgersmc.nexus.i18n.LangService
import net.badgersmc.nexus.paper.commands.annotations.Permission
import net.badgersmc.nexus.paper.commands.annotations.Subcommand
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/**
 * Player-facing `/shop` command (ItemShops parity sub-project 1). Menu-driven,
 * matching ItemShops: list / edit / trust / untrust / delete / breakdelete.
 */
@Command(name = "shop", description = "Manage your shops", aliases = ["shops"])
class ShopCommands(
    private val management: ShopManagementService,
    private val lang: LangService,
) {
    @Subcommand("list")
    @Permission("enthusiamarket.shop.use")
    fun list(@Context sender: CommandSender) {
        val player = sender as? Player ?: run { sender.sendMessage(lang.msg("shop.cmd.players_only")); return }
        val shops = management.shopsOwnedBy(player.uniqueId)
        if (shops.isEmpty()) {
            player.sendMessage(lang.msg("shop.cmd.none_owned"))
            return
        }
        player.sendMessage(lang.msg("shop.cmd.list_header", "count" to shops.size))
        for (s in shops) {
            val sellName = ItemStackSerializer.deserialize(s.sellItem)?.type?.name?.lowercase() ?: "?"
            player.sendMessage(
                lang.msg(
                    "shop.cmd.list_line",
                    "world" to s.signWorld, "x" to s.signX, "y" to s.signY, "z" to s.signZ,
                    "sell_amt" to s.sellAmount, "sell" to sellName, "cost" to s.costAmount,
                )
            )
        }
    }
}
```

- [ ] **Step 3: Build to confirm the command class compiles + is discoverable**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew compileKotlin -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: BUILD SUCCESSFUL. (Nexus auto-discovers `@Command` classes via `registerPaperCommands`; no manual wiring.)

- [ ] **Step 4: Commit**

```bash
cd /d/BadgersMC-Dev/EnthusiaMarket && git add src/main/kotlin/net/badgersmc/em/infrastructure/commands/ShopCommands.kt src/main/resources/lang/en_US.yml
git commit -m "feat(shop): /shop command skeleton + /shop list

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 5: `/shop trust` + `/shop untrust` + BulkTrustMenu

**Files:**
- Create: `src/main/kotlin/net/badgersmc/em/interaction/gui/BulkTrustMenu.kt`
- Modify: `src/main/kotlin/net/badgersmc/em/infrastructure/commands/ShopCommands.kt`

- [ ] **Step 1: Implement BulkTrustMenu (mirror TrustManageMenu pattern)**

```kotlin
package net.badgersmc.em.interaction.gui

import com.github.stefvanschie.inventoryframework.adventuresupport.ComponentHolder
import com.github.stefvanschie.inventoryframework.gui.GuiItem
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui
import com.github.stefvanschie.inventoryframework.pane.StaticPane
import net.badgersmc.em.application.ItemStackSerializer
import net.badgersmc.em.application.ShopManagementService
import net.badgersmc.em.domain.shop.Shop
import net.badgersmc.nexus.i18n.LangService
import net.badgersmc.em.interaction.Menu
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.UUID

/**
 * Pick which of the owner's shops to trust/untrust [targetName] on. Clicking a
 * shop icon toggles its selection; the confirm button applies via
 * [ShopManagementService]. Up to 45 shops (one chest page) — owners with more
 * use `/shop trust <player> all`.
 */
class BulkTrustMenu(
    private val owner: UUID,
    private val target: UUID,
    private val targetName: String,
    private val management: ShopManagementService,
    private val lang: LangService,
) : Menu {

    private val selected = mutableSetOf<Long>()

    override fun open(player: Player) {
        val shops = management.shopsOwnedBy(owner).take(45)
        val gui = ChestGui(6, ComponentHolder.of(lang.msg("gui.shop.trust.title", "name" to targetName)))
        val pane = StaticPane(9, 6)
        shops.forEachIndexed { idx, shop ->
            pane.addItem(GuiItem(icon(shop, shop.id in selected)) {
                it.isCancelled = true
                if (shop.id in selected) selected.remove(shop.id) else selected.add(shop.id)
                open(player) // re-render
            }, idx % 9, idx / 9)
        }
        pane.addItem(GuiItem(named(Material.LIME_STAINED_GLASS_PANE, lang.msg("gui.shop.trust.confirm"))) {
            it.isCancelled = true
            val n = management.trust(owner, target, selected.toList())
            player.closeInventory()
            player.sendMessage(lang.msg("shop.cmd.trusted_all", "name" to targetName, "count" to n))
        }, 8, 5)
        gui.addPane(pane)
        gui.show(player)
    }

    private fun icon(shop: Shop, sel: Boolean): ItemStack {
        val base = ItemStackSerializer.deserialize(shop.sellItem) ?: ItemStack(Material.CHEST)
        val meta = base.itemMeta ?: return base
        meta.displayName(lang.msg("gui.shop.trust.icon", "world" to shop.signWorld, "x" to shop.signX, "y" to shop.signY, "z" to shop.signZ, "sel" to sel))
        base.itemMeta = meta
        return base
    }

    private fun named(material: Material, name: Component): ItemStack {
        val item = ItemStack(material)
        val meta = item.itemMeta ?: return item
        meta.displayName(name)
        item.itemMeta = meta
        return item
    }
}
```

- [ ] **Step 2: Add `trust`/`untrust` subcommands to ShopCommands**

Add to `ShopCommands` (inject nothing new — `management` + `lang` already present):

```kotlin
    @Subcommand("trust")
    @Permission("enthusiamarket.shop.use")
    fun trust(
        @Context sender: CommandSender,
        @net.badgersmc.nexus.commands.annotations.Arg("player") name: String,
        @net.badgersmc.nexus.commands.annotations.Arg("mode") mode: String = "menu",
    ) {
        val player = sender as? Player ?: run { sender.sendMessage(lang.msg("shop.cmd.players_only")); return }
        val target = org.bukkit.Bukkit.getOfflinePlayer(name)
        if (target.name == null && !target.hasPlayedBefore()) {
            player.sendMessage(lang.msg("shop.cmd.unknown_player", "name" to name)); return
        }
        if (mode.equals("all", ignoreCase = true)) {
            val n = management.trustAll(player.uniqueId, target.uniqueId)
            player.sendMessage(lang.msg("shop.cmd.trusted_all", "name" to name, "count" to n))
            return
        }
        net.badgersmc.em.interaction.gui.BulkTrustMenu(player.uniqueId, target.uniqueId, name, management, lang).open(player)
    }

    @Subcommand("untrust")
    @Permission("enthusiamarket.shop.use")
    fun untrust(
        @Context sender: CommandSender,
        @net.badgersmc.nexus.commands.annotations.Arg("player") name: String,
        @net.badgersmc.nexus.commands.annotations.Arg("mode") mode: String = "all",
    ) {
        val player = sender as? Player ?: run { sender.sendMessage(lang.msg("shop.cmd.players_only")); return }
        val target = org.bukkit.Bukkit.getOfflinePlayer(name)
        if (target.name == null && !target.hasPlayedBefore()) {
            player.sendMessage(lang.msg("shop.cmd.unknown_player", "name" to name)); return
        }
        val n = management.untrustAll(player.uniqueId, target.uniqueId)
        player.sendMessage(lang.msg("shop.cmd.untrusted_all", "name" to name, "count" to n))
    }
```

Add the GUI lang keys to `en_US.yml` under `gui.shop`:

```yaml
    trust:
      title: "<dark_gray>Trust <name> on…"
      icon: "<white><world> <x>,<y>,<z> <gray>(<sel>)"
      confirm: "<green>Confirm"
```

- [ ] **Step 3: Build**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew compileKotlin -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
cd /d/BadgersMC-Dev/EnthusiaMarket && git add src/main/kotlin/net/badgersmc/em/interaction/gui/BulkTrustMenu.kt src/main/kotlin/net/badgersmc/em/infrastructure/commands/ShopCommands.kt src/main/resources/lang/en_US.yml
git commit -m "feat(shop): /shop trust|untrust + BulkTrustMenu

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 6: `/shop delete` + DeleteShopsMenu, and `/shop edit` + OwnedShopsMenu

**Files:**
- Create: `src/main/kotlin/net/badgersmc/em/interaction/gui/DeleteShopsMenu.kt`
- Create: `src/main/kotlin/net/badgersmc/em/interaction/gui/OwnedShopsMenu.kt`
- Modify: `src/main/kotlin/net/badgersmc/em/infrastructure/commands/ShopCommands.kt`

- [ ] **Step 1: Implement OwnedShopsMenu (list → click → ShopEditMenu)**

```kotlin
package net.badgersmc.em.interaction.gui

import com.github.stefvanschie.inventoryframework.adventuresupport.ComponentHolder
import com.github.stefvanschie.inventoryframework.gui.GuiItem
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui
import com.github.stefvanschie.inventoryframework.pane.StaticPane
import net.badgersmc.em.application.ItemStackSerializer
import net.badgersmc.em.application.ShopManagementService
import net.badgersmc.em.domain.shop.ShopRepository
import net.badgersmc.nexus.i18n.LangService
import net.badgersmc.em.interaction.Menu
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.UUID

/** Paginated (first 45) list of the owner's shops; click to open the edit menu. */
class OwnedShopsMenu(
    private val owner: UUID,
    private val shopRepository: ShopRepository,
    private val management: ShopManagementService,
    private val lang: LangService,
) : Menu {
    override fun open(player: Player) {
        val shops = management.shopsOwnedBy(owner).take(45)
        val gui = ChestGui(6, ComponentHolder.of(lang.msg("gui.shop.owned.title")))
        val pane = StaticPane(9, 6)
        shops.forEachIndexed { idx, shop ->
            val base = ItemStackSerializer.deserialize(shop.sellItem) ?: ItemStack(Material.CHEST)
            val meta = base.itemMeta
            if (meta != null) {
                meta.displayName(lang.msg("gui.shop.owned.icon", "sell_amt" to shop.sellAmount, "cost" to shop.costAmount, "world" to shop.signWorld, "x" to shop.signX, "y" to shop.signY, "z" to shop.signZ))
                base.itemMeta = meta
            }
            pane.addItem(GuiItem(base) {
                it.isCancelled = true
                ShopEditMenu(shop, shopRepository, management, lang).open(player)
            }, idx % 9, idx / 9)
        }
        gui.addPane(pane)
        gui.show(player)
    }
}
```

- [ ] **Step 2: Implement DeleteShopsMenu**

```kotlin
package net.badgersmc.em.interaction.gui

import com.github.stefvanschie.inventoryframework.adventuresupport.ComponentHolder
import com.github.stefvanschie.inventoryframework.gui.GuiItem
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui
import com.github.stefvanschie.inventoryframework.pane.StaticPane
import net.badgersmc.em.application.ItemStackSerializer
import net.badgersmc.em.application.ShopManagementService
import net.badgersmc.nexus.i18n.LangService
import net.badgersmc.em.interaction.Menu
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.UUID

/** Click a shop icon to delete it (with a confirm click). First 45 shops. */
class DeleteShopsMenu(
    private val owner: UUID,
    private val management: ShopManagementService,
    private val lang: LangService,
) : Menu {
    private val armed = mutableSetOf<Long>()
    override fun open(player: Player) {
        val shops = management.shopsOwnedBy(owner).take(45)
        val gui = ChestGui(6, ComponentHolder.of(lang.msg("gui.shop.delete.title")))
        val pane = StaticPane(9, 6)
        shops.forEachIndexed { idx, shop ->
            val base = ItemStackSerializer.deserialize(shop.sellItem) ?: ItemStack(Material.CHEST)
            val meta = base.itemMeta
            if (meta != null) {
                val key = if (shop.id in armed) "gui.shop.delete.icon_armed" else "gui.shop.delete.icon"
                meta.displayName(lang.msg(key, "world" to shop.signWorld, "x" to shop.signX, "y" to shop.signY, "z" to shop.signZ))
                base.itemMeta = meta
            }
            pane.addItem(GuiItem(base) {
                it.isCancelled = true
                if (shop.id in armed) {
                    management.delete(owner, shop.id)
                    armed.remove(shop.id)
                    player.sendMessage(lang.msg("shop.delete.done"))
                } else {
                    armed.add(shop.id)
                }
                open(player)
            }, idx % 9, idx / 9)
        }
        gui.addPane(pane)
        gui.show(player)
    }
}
```

- [ ] **Step 3: Add `edit` + `delete` subcommands to ShopCommands**

Inject `ShopRepository` into `ShopCommands` (add `private val shopRepository: net.badgersmc.em.domain.shop.ShopRepository,` to the constructor). Add:

```kotlin
    @Subcommand("edit")
    @Permission("enthusiamarket.shop.use")
    fun edit(@Context sender: CommandSender) {
        val player = sender as? Player ?: run { sender.sendMessage(lang.msg("shop.cmd.players_only")); return }
        if (management.shopsOwnedBy(player.uniqueId).isEmpty()) {
            player.sendMessage(lang.msg("shop.cmd.none_owned")); return
        }
        net.badgersmc.em.interaction.gui.OwnedShopsMenu(player.uniqueId, shopRepository, management, lang).open(player)
    }

    @Subcommand("delete")
    @Permission("enthusiamarket.shop.use")
    fun delete(
        @Context sender: CommandSender,
        @net.badgersmc.nexus.commands.annotations.Arg("mode") mode: String = "menu",
    ) {
        val player = sender as? Player ?: run { sender.sendMessage(lang.msg("shop.cmd.players_only")); return }
        if (mode.equals("all", ignoreCase = true)) {
            if (!player.hasPermission("enthusiamarket.shop.delete.all")) {
                player.sendMessage(lang.msg("shop.cmd.no_permission")); return
            }
            val n = management.deleteAll(player.uniqueId)
            player.sendMessage(lang.msg("shop.cmd.deleted_all", "count" to n))
            return
        }
        if (management.shopsOwnedBy(player.uniqueId).isEmpty()) {
            player.sendMessage(lang.msg("shop.cmd.none_owned")); return
        }
        net.badgersmc.em.interaction.gui.DeleteShopsMenu(player.uniqueId, management, lang).open(player)
    }
```

Add lang keys to `en_US.yml` under `gui.shop`:

```yaml
    owned:
      title: "<dark_gray>Your shops (click to edit)"
      icon: "<green><sell_amt>x <gray>for <gold><cost> <dark_gray>| <white><world> <x>,<y>,<z>"
    delete:
      title: "<dark_red>Delete shops"
      icon: "<white><world> <x>,<y>,<z> <gray>(click to arm)"
      icon_armed: "<red>Click again to DELETE <white><world> <x>,<y>,<z>"
```

And under `shop` (top level), add: `delete: { done: "<yellow>Shop deleted." }` and `edit: { saved: "<green>Shop updated.", not_owner: "<red>You don't own this shop." }` if not already present (the `shop.edit.not_owner` key already exists — do not duplicate).

- [ ] **Step 4: Build**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew compileKotlin -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
cd /d/BadgersMC-Dev/EnthusiaMarket && git add src/main/kotlin/net/badgersmc/em/interaction/gui/OwnedShopsMenu.kt src/main/kotlin/net/badgersmc/em/interaction/gui/DeleteShopsMenu.kt src/main/kotlin/net/badgersmc/em/infrastructure/commands/ShopCommands.kt src/main/resources/lang/en_US.yml
git commit -m "feat(shop): /shop edit (OwnedShopsMenu) + /shop delete (DeleteShopsMenu)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 7: `/shop breakdelete` + BlockProtectionListener wiring

**Files:**
- Modify: `src/main/kotlin/net/badgersmc/em/infrastructure/commands/ShopCommands.kt`
- Modify: `src/main/kotlin/net/badgersmc/em/infrastructure/listeners/BlockProtectionListener.kt`

- [ ] **Step 1: Add `breakdelete` subcommand to ShopCommands**

Inject `BreakDeleteMode` (add `private val breakDelete: net.badgersmc.em.application.BreakDeleteMode,` to the constructor). Add:

```kotlin
    @Subcommand("breakdelete")
    @Permission("enthusiamarket.shop.use")
    fun breakDeleteCmd(
        @Context sender: CommandSender,
        @net.badgersmc.nexus.commands.annotations.Arg("mode") mode: String = "on",
    ) {
        val player = sender as? Player ?: run { sender.sendMessage(lang.msg("shop.cmd.players_only")); return }
        val durationMs = net.badgersmc.em.application.BreakDeleteMode.parseDurationMs(mode)
        if (durationMs == null) {
            breakDelete.disable(player.uniqueId)
            player.sendMessage(lang.msg("shop.cmd.breakdelete_off"))
            return
        }
        breakDelete.enable(player.uniqueId, durationMs)
        player.sendMessage(lang.msg("shop.cmd.breakdelete_on", "minutes" to (durationMs / 60_000)))
    }
```

- [ ] **Step 2: Read BlockProtectionListener to find the sign-break deny path**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && sed -n '40,100p' src/main/kotlin/net/badgersmc/em/infrastructure/listeners/BlockProtectionListener.kt`
Note the constructor (for adding `BreakDeleteMode`), the event handler that resolves a shop via
`shopRepository.findBySign(...)`, and where it cancels the break for a non-owner/non-trusted player.

- [ ] **Step 3: Wire BreakDeleteMode into BlockProtectionListener**

Add `BreakDeleteMode` to the listener's constructor (Nexus injects the `@Component`). In the
sign-break handler, BEFORE the existing protection logic cancels the event: if the broken block
resolves to a shop whose `owner == player.uniqueId` AND `breakDelete.isActive(player.uniqueId)`,
then allow the break (do NOT cancel) and call `management.delete(player.uniqueId, shop.id)` (inject
`ShopManagementService` too), and send `shop.delete.done`. Otherwise fall through to the existing
protection behaviour unchanged.

Exact shape (adapt variable names to the file from Step 2):

```kotlin
        val shop = shopRepository.findBySign(world.name, block.x, block.y, block.z)
        if (shop != null && shop.owner == player.uniqueId && breakDelete.isActive(player.uniqueId)) {
            management.delete(player.uniqueId, shop.id)
            player.sendMessage(lang.msg("shop.delete.done"))
            return // allow the break; shop row already removed
        }
        // ... existing protection logic unchanged ...
```

- [ ] **Step 4: Build + fix any listener-test constructor breakage**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew compileKotlin compileTestKotlin -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: BUILD SUCCESSFUL. If a `BlockProtectionListener` test constructs it directly, add the
new `BreakDeleteMode` + `ShopManagementService` + `lang` args (use `mockk(relaxed = true)` /
real instances as the test needs).

- [ ] **Step 5: Commit**

```bash
cd /d/BadgersMC-Dev/EnthusiaMarket && git add src/main/kotlin/net/badgersmc/em/infrastructure/commands/ShopCommands.kt src/main/kotlin/net/badgersmc/em/infrastructure/listeners/BlockProtectionListener.kt
git commit -m "feat(shop): /shop breakdelete timed mode + BlockProtectionListener wiring

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 8: Permission DSL nodes + final verification

**Files:**
- Modify: `build.gradle.kts` (nexus-permissions tree)
- Modify: `docs/tasks.md`

- [ ] **Step 1: Add the new permission nodes to the DSL**

In `build.gradle.kts`, inside the `configure<...NexusPermissionsExtension> { tree { ... } }` block,
add `child("shop.use")` under the `enthusiamarket.player` node, and add a standalone node for the
admin-gated `delete all`:

Under the `node("enthusiamarket.player", ...)` block, add:
```kotlin
            child("shop.use")
```
And after the player node, add:
```kotlin
        node("enthusiamarket.shop.delete.all", default = Default.OP, description = "Delete all of a player's shops")
```

- [ ] **Step 2: Regenerate + verify the nodes land in the staged paper-plugin.yml**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew processResources -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain && grep -E "shop.use|shop.delete.all" build/resources/main/paper-plugin.yml`
Expected: both `enthusiamarket.shop.use` and `enthusiamarket.shop.delete.all` present.

- [ ] **Step 3: Full verification gate**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew clean detekt test shadowJar -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: BUILD SUCCESSFUL, detekt 0 issues, all tests pass.

- [ ] **Step 4: Mark progress + commit**

Add a `[x]` note to `docs/tasks.md` (append a line: `- [x] ItemShops parity SP1 — /shop management commands (list/edit/trust/untrust/delete/breakdelete)`). Then:

```bash
cd /d/BadgersMC-Dev/EnthusiaMarket && git add build.gradle.kts docs/tasks.md
git commit -m "feat(shop): permission nodes for /shop; SP1 complete

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

- [ ] **Step 5: Manual QA checklist (for the PR description — non-op player)**

1. `/shop list` with no shops → "You own no shops"; with shops → chat list.
2. Create a shop, `/shop edit` → menu → click shop → change sell amount + cost → save → re-open confirms persisted.
3. `/shop trust <player>` → menu → select shops → confirm; `/shop trust <player> all` → trusts all.
4. `/shop untrust <player>` → untrusts all.
5. `/shop delete` → menu → click twice to delete; `/shop delete all` → blocked without `enthusiamarket.shop.delete.all`, works with it.
6. `/shop breakdelete 1m` → break your own shop sign within 1 min → shop deleted + sign breaks; after expiry → break is blocked again.

---

## Self-Review Notes (for the implementer)

Confirm these by reading the file BEFORE writing (each is a single-symbol check):

1. **`BlockProtectionListener` constructor + break handler** (Task 7) — read the file; adapt the wiring snippet to its actual variable names and the exact point where it cancels the break. It already has `shopRepository` + `lang`; you add `BreakDeleteMode` + `ShopManagementService`.
2. **Existing `ShopEditMenu` call sites** (Task 3 Step 4) — grep first; the only main-code caller after this plan is `OwnedShopsMenu` (Task 6). If a listener opens `ShopEditMenu`, inject `ShopManagementService` there.
3. **`SignDirection` import** — `net.badgersmc.em.domain.shop.SignDirection` (used in test shop builders).
4. **Nexus `@Arg` optional-arg support** — defaults are via Kotlin default params (`mode: String = "menu"`). If the Nexus version rejects defaulted `@Arg`, split into two `@Subcommand` overloads (e.g. `trust` and `trust all`) — check an existing AdminCommands subcommand with an optional arg (`auctionStart` uses `duration: String? = null`).
5. **`Default` import in build.gradle.kts** — `import net.badgersmc.nexus.permissions.Default` is already at the top of the file (used by the existing tree). Reuse it.
