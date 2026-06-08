# Guild Policy Management GUI — Implementation Plan (PR-B)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Let MANAGE_SHOPS guild members set/edit/remove tariffs & embargoes in-game via `/em guild policy` — a chest GUI listing current policies (click to adjust) plus a guild picker to add new targets. (The engine is PR-A #50; broadcasts are PR-C #51. This is the missing UI.)

**Architecture:** A `GuildProvider.listGuilds()` port addition (thin LumaGuilds `getAllGuilds()` map); a `/em guild policy` command (resolve the actor's guild, gate on MANAGE_SHOPS, open the menu); `GuildTradePolicyMenu` (paginated current policies, click-to-mutate via the already-tested `GuildTradePolicyService`); `GuildPickerMenu` (paginated other guilds → add a default 10% tariff). Click→value math lives in pure helpers (TDD); the IFramework rendering is build-verified + construct-tested like the other menus.

**Tech Stack:** Kotlin 2.0.0, Nexus DI + commands, InventoryFramework (IF) chest GUIs, LumaGuilds API, JUnit 5 + MockK, detekt 1.23.8.

**Reference spec:** `docs/superpowers/specs/2026-06-08-guild-trade-policies-design.md` (§3 Interaction, §7 PR-B).

**Standing rules (every task):**
- Prefix bash with `cd <REPO> &&`. On Hermes' box prefix gradle with `export JAVA_HOME=/opt/data/jdk-21.0.11+10 &&`; repo `/opt/data/EnthusiaMarket`, jar `/opt/data/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar`.
- Every gradle command includes `-Plumaguilds.jar=<JAR> --no-daemon --console=plain`.
- LF→CRLF warnings expected. Branch `feat/guild-policy-gui` off current `main`. Do not push to BadgersMC (coordinator opens the PR; Hermes pushes to `fork` only).
- TDD where noted. Commit after every task.
- Gate rule: compare each `Run:` to `Expected:`; mismatch → STOP, fix, re-run; HALT after 3 tries.

---

## CONFIRMED API SYMBOLS (verified against the repo)

- **`GuildTradePolicyService`** (`application/`, `@Service`): `list(ownerGuildId: String): List<GuildTradePolicy>`; `setTariff(actor, owner, target, ratePct): PolicyResult`; `setEmbargo(actor, owner, target): PolicyResult`; `clear(actor, owner, target): PolicyResult`; `companion { const val MAX_TARIFF_PCT = 99 }`. `PolicyResult { Ok; Denied; Invalid(reason) }`. These already gate MANAGE_SHOPS + self-target and fire the broadcast event (PR-C).
- **`GuildTradePolicy`** (`domain/guild/`): `(ownerGuildId, targetGuildId, kind: PolicyKind, ratePct)`. `PolicyKind { TARIFF, EMBARGO }`.
- **`GuildProvider`** (`domain/ports/`): `guildOf(player: UUID): GuildRef?`, `guildById(id: String): GuildRef?`, `hasShopPermission(player, guildId, GuildPermission.MANAGE_SHOPS): Boolean`, `GuildRef(id: String, name: String, tag: String = "", emoji: String = "")`. **Add `listGuilds(): List<GuildRef>`.** Only impl is `LumaGuildsGuildProvider` (`@Component`); it has a private `toGuildRef(guild: Guild)` and `resolvedGuildService: GuildService`.
- **LumaGuilds** `GuildService.getAllGuilds(): Set<net.lumalyte.lg.domain.entities.Guild>`; `Guild.getId(): UUID`, `Guild.getName(): String`.
- **`interaction/Menu`** — interface with `fun open(player: Player)`. Menus implement it.
- **IF idioms** (`com.github.stefvanschie.inventoryframework.*`): `ChestGui(rows, com.github.stefvanschie.inventoryframework.adventuresupport.ComponentHolder.of(titleComponent))`; `StaticPane(width, height)` or `StaticPane(x, y, w, h)`; `PaginatedPane(0,0,9,5)` + `OutlinePane(0,0,9,5, Pane.Priority.LOWEST)` per page (see `AuctionBrowserMenu`); `GuiItem(itemStack) { event -> event.isCancelled = true; … }`; `gui.addPane(pane)`; `gui.show(player)`. Click flags: `event.isLeftClick`, `event.isRightClick`, `event.isShiftClick` (`org.bukkit.event.inventory.InventoryClickEvent`). Build an `ItemStack` + set its display name/lore via `ItemMeta` (mirror `AuctionBrowserMenu.itemStack {}` / `ShopEditMenu`).
- **Command:** `AdminCommands` (`@Command(name="em")`) uses multi-segment `@Subcommand("stall members add")` + `@Permission(node)` + `@Context sender: CommandSender`. Inject new deps into its constructor (it already injects many services + `lang`). Player-only guard: `val player = sender as? Player ?: run { sender.sendMessage(lang.msg("command.players_only")); return }`.
- **Perm DSL** (`build.gradle.kts`, `permissionTree`): `node("name", default = Default.TRUE, description = "…")`. `enthusiamarket.guild.policy` is NOT present — add it.
- **Menu test precedent:** `ShopEditMenuTest` — construct with relaxed mocks + `assertNotNull`; for the open-guard, a `mockk<Player>(relaxed=true)` with `every { uniqueId } returns …` + `verify` the reject message. Mirror it.
- **`LangService`**: `lang.msg(key, "tok" to v): Component`; `player.sendMessage(...)`.

---

## Task 1: GuildProvider.listGuilds

**Files:**
- Modify: `src/main/kotlin/net/badgersmc/em/domain/ports/GuildProvider.kt`
- Modify: `src/main/kotlin/net/badgersmc/em/infrastructure/lumaguilds/LumaGuildsGuildProvider.kt`
- Modify: any other `GuildProvider` impl (grep `: GuildProvider` — at minimum the Luma one; add to a NoOp/test fake if present).

- [ ] **Step 1: Add the port method** — in `GuildProvider`, next to `guildById`:
```kotlin
    /** Every guild known to the backing system (for pickers). */
    fun listGuilds(): List<GuildRef>
```

- [ ] **Step 2: Implement in LumaGuildsGuildProvider** — after `guildById`:
```kotlin
    override fun listGuilds(): List<GuildProvider.GuildRef> =
        resolvedGuildService.getAllGuilds().map { toGuildRef(it) }
```

- [ ] **Step 3: Build** — `./gradlew compileKotlin compileTestKotlin -Plumaguilds.jar=… --no-daemon --console=plain`
Expected: BUILD SUCCESSFUL. If a test fake implements `GuildProvider` and now fails to compile, add `override fun listGuilds() = emptyList<GuildProvider.GuildRef>()` to it.

- [ ] **Step 4: Commit** — `feat(guild): GuildProvider.listGuilds (all guilds, for the policy picker)` + Co-Authored-By trailer.

---

## Task 2: Permission node

**Files:** Modify `build.gradle.kts`

- [ ] **Step 1: Add the node** — inside `permissionTree`, near the other `enthusiamarket.stall.*`/player nodes:
```kotlin
        node("enthusiamarket.guild.policy", default = Default.TRUE, description = "Open /em guild policy to manage guild tariffs/embargoes (MANAGE_SHOPS still required in-guild)")
```

- [ ] **Step 2: Build** — `./gradlew generateNexusPermissions compileKotlin -Plumaguilds.jar=… --no-daemon --console=plain` → BUILD SUCCESSFUL; `grep -n "enthusiamarket.guild.policy" build/resources/main/paper-plugin.yml` prints the generated node.
- [ ] **Step 3: Commit** — `feat(perms): enthusiamarket.guild.policy node` + trailer.

---

## Task 3: Tariff-step helpers + GuildTradePolicyMenu

**Files:**
- Create: `src/main/kotlin/net/badgersmc/em/interaction/gui/GuildTradePolicyMenu.kt`
- Test: `src/test/kotlin/net/badgersmc/em/interaction/gui/GuildTradePolicyMenuTest.kt`

Click map per policy icon: **left-click** = +5% tariff (capped at 99; if currently embargo, becomes a 5% tariff), **right-click** = −5% tariff (floored at 5), **shift-left** = set embargo, **shift-right** = remove. Each click calls the matching `GuildTradePolicyService` method (which re-validates + broadcasts) then re-renders. The step math is pure + tested; the menu itself is construct-tested.

- [ ] **Step 1: Failing tests** (mirror `ShopEditMenuTest`; test the pure step companion + construction)
```kotlin
package net.badgersmc.em.interaction.gui

import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class GuildTradePolicyMenuTest {
    @Test fun `step up adds 5 capped at MAX`() {
        assertEquals(20, GuildTradePolicyMenu.stepUp(15))
        assertEquals(99, GuildTradePolicyMenu.stepUp(97))   // cap
    }
    @Test fun `step down subtracts 5 floored at 5`() {
        assertEquals(10, GuildTradePolicyMenu.stepDown(15))
        assertEquals(5, GuildTradePolicyMenu.stepDown(5))   // floor
    }
    @Test fun `menu constructs without throwing`() {
        val menu = GuildTradePolicyMenu(
            actor = java.util.UUID.randomUUID(), ownerGuildId = "g1",
            policyService = mockk(relaxed = true), guildProvider = mockk(relaxed = true), lang = mockk(relaxed = true),
        )
        assertNotNull(menu)
    }
}
```

- [ ] **Step 2: RED** — `./gradlew test --tests "*GuildTradePolicyMenuTest" -Plumaguilds.jar=… --no-daemon --console=plain` → FAIL.

- [ ] **Step 3: Implement**
```kotlin
package net.badgersmc.em.interaction.gui

import com.github.stefvanschie.inventoryframework.adventuresupport.ComponentHolder
import com.github.stefvanschie.inventoryframework.gui.GuiItem
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui
import com.github.stefvanschie.inventoryframework.pane.OutlinePane
import com.github.stefvanschie.inventoryframework.pane.Pane
import com.github.stefvanschie.inventoryframework.pane.PaginatedPane
import com.github.stefvanschie.inventoryframework.pane.StaticPane
import net.badgersmc.em.application.GuildTradePolicyService
import net.badgersmc.em.domain.guild.GuildTradePolicy
import net.badgersmc.em.domain.guild.PolicyKind
import net.badgersmc.em.domain.ports.GuildProvider
import net.badgersmc.em.interaction.Menu
import net.badgersmc.nexus.i18n.LangService
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.UUID

/**
 * MANAGE_SHOPS GUI listing a guild's tariff/embargo policies. Click a policy to
 * adjust it; the "+" button opens the guild picker. All mutations route through
 * [GuildTradePolicyService] (which re-validates + broadcasts).
 */
class GuildTradePolicyMenu(
    private val actor: UUID,
    private val ownerGuildId: String,
    private val policyService: GuildTradePolicyService,
    private val guildProvider: GuildProvider,
    private val lang: LangService,
) : Menu {

    override fun open(player: Player) = render(player)

    private fun render(player: Player) {
        val policies = policyService.list(ownerGuildId)
        val gui = ChestGui(6, ComponentHolder.of(lang.msg("gui.guildpolicy.title")))
        val pages = PaginatedPane(0, 0, 9, 5)
        val perPage = 45
        val pageCount = maxOf(1, (policies.size + perPage - 1) / perPage)
        for (p in 0 until pageCount) {
            val pane = OutlinePane(0, 0, 9, 5, Pane.Priority.LOWEST)
            policies.drop(p * perPage).take(perPage).forEach { policy ->
                pane.addItem(GuiItem(icon(policy)) { ev -> ev.isCancelled = true; mutate(player, policy, ev.isLeftClick, ev.isShiftClick) })
            }
            pages.addPane(p, pane)
        }
        gui.addPane(pages)
        gui.addPane(controls(player, gui, pages, pageCount))
        gui.show(player)
    }

    private fun controls(player: Player, gui: ChestGui, pages: PaginatedPane, pageCount: Int): StaticPane {
        val bar = StaticPane(0, 5, 9, 1)
        bar.addItem(GuiItem(named(Material.ARROW, lang.msg("gui.common.prev"))) {
            it.isCancelled = true; if (pages.page > 0) { pages.page -= 1; gui.update() }
        }, 0, 0)
        bar.addItem(GuiItem(named(Material.EMERALD, lang.msg("gui.guildpolicy.add"))) {
            it.isCancelled = true
            GuildPickerMenu(actor, ownerGuildId, policyService, guildProvider, lang).open(player)
        }, 4, 0)
        bar.addItem(GuiItem(named(Material.ARROW, lang.msg("gui.common.next"))) {
            it.isCancelled = true; if (pages.page < pageCount - 1) { pages.page += 1; gui.update() }
        }, 8, 0)
        return bar
    }

    private fun mutate(player: Player, policy: GuildTradePolicy, left: Boolean, shift: Boolean) {
        val result = when {
            shift && left -> policyService.setEmbargo(actor, ownerGuildId, policy.targetGuildId)
            shift && !left -> policyService.clear(actor, ownerGuildId, policy.targetGuildId)
            left -> policyService.setTariff(actor, ownerGuildId, policy.targetGuildId, stepUp(currentRate(policy)))
            else -> policyService.setTariff(actor, ownerGuildId, policy.targetGuildId, stepDown(currentRate(policy)))
        }
        if (result is GuildTradePolicyService.PolicyResult.Invalid) player.sendMessage(lang.msg("gui.guildpolicy.invalid", "reason" to result.reason))
        render(player)
    }

    private fun currentRate(policy: GuildTradePolicy): Int =
        if (policy.kind == PolicyKind.TARIFF) policy.ratePct else MIN_TARIFF_PCT

    private fun icon(policy: GuildTradePolicy): ItemStack {
        val target = guildProvider.guildById(policy.targetGuildId)?.name ?: policy.targetGuildId
        val (mat, line) = if (policy.kind == PolicyKind.EMBARGO) {
            Material.BARRIER to lang.msg("gui.guildpolicy.icon_embargo", "target" to target)
        } else {
            Material.GOLD_INGOT to lang.msg("gui.guildpolicy.icon_tariff", "target" to target, "rate" to policy.ratePct)
        }
        return named(mat, line, lang.msg("gui.guildpolicy.icon_controls"))
    }

    private fun named(material: Material, name: Component, vararg lore: Component): ItemStack =
        ItemStack(material).apply {
            itemMeta = itemMeta?.also { m -> m.displayName(name); if (lore.isNotEmpty()) m.lore(lore.toList()) }
        }

    companion object {
        const val MIN_TARIFF_PCT = 5
        fun stepUp(current: Int): Int = (current + 5).coerceAtMost(GuildTradePolicyService.MAX_TARIFF_PCT)
        fun stepDown(current: Int): Int = (current - 5).coerceAtLeast(MIN_TARIFF_PCT)
    }
}
```

- [ ] **Step 4: GREEN** — same command → PASS.
- [ ] **Step 5: Commit** — `feat(guild): GuildTradePolicyMenu (list + click-to-edit policies)` + trailer.

---

## Task 4: GuildPickerMenu

**Files:**
- Create: `src/main/kotlin/net/badgersmc/em/interaction/gui/GuildPickerMenu.kt`
- Test: `src/test/kotlin/net/badgersmc/em/interaction/gui/GuildPickerMenuTest.kt`

Lists every other guild not already policied; clicking one creates a default `MIN_TARIFF_PCT`-floored 10% tariff (`DEFAULT_NEW_TARIFF`) and returns to the policy menu.

- [ ] **Step 1: Failing test**
```kotlin
package net.badgersmc.em.interaction.gui

import io.mockk.every
import io.mockk.mockk
import net.badgersmc.em.application.GuildTradePolicyService
import net.badgersmc.em.domain.ports.GuildProvider
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class GuildPickerMenuTest {
    @Test fun `selectable excludes own guild and already-policied targets`() {
        val all = listOf(GuildProvider.GuildRef("g1","Self"), GuildProvider.GuildRef("g2","Beta"), GuildProvider.GuildRef("g3","Gamma"))
        val out = GuildPickerMenu.selectable(all, ownerGuildId = "g1", existingTargets = setOf("g2"))
        assertEquals(listOf("g3"), out.map { it.id })
    }
    @Test fun `menu constructs without throwing`() {
        val gp = mockk<GuildProvider>(relaxed = true); every { gp.listGuilds() } returns emptyList()
        val ps = mockk<GuildTradePolicyService>(relaxed = true); every { ps.list("g1") } returns emptyList()
        assertNotNull(GuildPickerMenu(UUID.randomUUID(), "g1", ps, gp, mockk(relaxed = true)))
    }
}
```

- [ ] **Step 2: RED** — `./gradlew test --tests "*GuildPickerMenuTest" …` → FAIL.

- [ ] **Step 3: Implement**
```kotlin
package net.badgersmc.em.interaction.gui

import com.github.stefvanschie.inventoryframework.adventuresupport.ComponentHolder
import com.github.stefvanschie.inventoryframework.gui.GuiItem
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui
import com.github.stefvanschie.inventoryframework.pane.OutlinePane
import com.github.stefvanschie.inventoryframework.pane.Pane
import com.github.stefvanschie.inventoryframework.pane.PaginatedPane
import com.github.stefvanschie.inventoryframework.pane.StaticPane
import net.badgersmc.em.application.GuildTradePolicyService
import net.badgersmc.em.domain.ports.GuildProvider
import net.badgersmc.em.interaction.Menu
import net.badgersmc.nexus.i18n.LangService
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.UUID

/** Picks a target guild for a new policy; seeds a default tariff then returns to the policy menu. */
class GuildPickerMenu(
    private val actor: UUID,
    private val ownerGuildId: String,
    private val policyService: GuildTradePolicyService,
    private val guildProvider: GuildProvider,
    private val lang: LangService,
) : Menu {

    override fun open(player: Player) {
        val existing = policyService.list(ownerGuildId).map { it.targetGuildId }.toSet()
        val targets = selectable(guildProvider.listGuilds(), ownerGuildId, existing)
        val gui = ChestGui(6, ComponentHolder.of(lang.msg("gui.guildpicker.title")))
        val pages = PaginatedPane(0, 0, 9, 5)
        val perPage = 45
        val pageCount = maxOf(1, (targets.size + perPage - 1) / perPage)
        for (p in 0 until pageCount) {
            val pane = OutlinePane(0, 0, 9, 5, Pane.Priority.LOWEST)
            targets.drop(p * perPage).take(perPage).forEach { ref ->
                pane.addItem(GuiItem(named(Material.PAPER, lang.msg("gui.guildpicker.entry", "name" to ref.name))) {
                    it.isCancelled = true
                    policyService.setTariff(actor, ownerGuildId, ref.id, DEFAULT_NEW_TARIFF)
                    GuildTradePolicyMenu(actor, ownerGuildId, policyService, guildProvider, lang).open(player)
                })
            }
            pages.addPane(p, pane)
        }
        gui.addPane(pages)
        val bar = StaticPane(0, 5, 9, 1)
        bar.addItem(GuiItem(named(Material.ARROW, lang.msg("gui.common.prev"))) { it.isCancelled = true; if (pages.page > 0) { pages.page -= 1; gui.update() } }, 0, 0)
        bar.addItem(GuiItem(named(Material.BARRIER, lang.msg("gui.common.back"))) {
            it.isCancelled = true; GuildTradePolicyMenu(actor, ownerGuildId, policyService, guildProvider, lang).open(player)
        }, 4, 0)
        bar.addItem(GuiItem(named(Material.ARROW, lang.msg("gui.common.next"))) { it.isCancelled = true; if (pages.page < pageCount - 1) { pages.page += 1; gui.update() } }, 8, 0)
        gui.addPane(bar)
        gui.show(player)
    }

    private fun named(material: Material, name: Component): ItemStack =
        ItemStack(material).apply { itemMeta = itemMeta?.also { it.displayName(name) } }

    companion object {
        const val DEFAULT_NEW_TARIFF = 10
        /** Guilds eligible as new policy targets: all guilds minus the owner and already-policied ids. */
        fun selectable(all: List<GuildProvider.GuildRef>, ownerGuildId: String, existingTargets: Set<String>): List<GuildProvider.GuildRef> =
            all.filter { it.id != ownerGuildId && it.id !in existingTargets }
    }
}
```

- [ ] **Step 4: GREEN** — same command → PASS.
- [ ] **Step 5: Commit** — `feat(guild): GuildPickerMenu (add a policy target)` + trailer.

---

## Task 5: /em guild policy command

**Files:** Modify `src/main/kotlin/net/badgersmc/em/infrastructure/commands/AdminCommands.kt`

- [ ] **Step 1: Inject deps + add the subcommand.** Add `policyService: GuildTradePolicyService` and `guildProvider: GuildProvider` to the `AdminCommands` constructor (it already injects `lang` + many services). Add:
```kotlin
    @Subcommand("guild policy")
    @Permission("enthusiamarket.guild.policy")
    fun guildPolicy(@Context sender: CommandSender) {
        val player = sender as? Player ?: run { sender.sendMessage(lang.msg("command.players_only")); return }
        val guild = guildProvider.guildOf(player.uniqueId) ?: run { player.sendMessage(lang.msg("guildpolicy.not_in_guild")); return }
        if (!guildProvider.hasShopPermission(player.uniqueId, guild.id, GuildProvider.GuildPermission.MANAGE_SHOPS)) {
            player.sendMessage(lang.msg("guildpolicy.no_permission")); return
        }
        GuildTradePolicyMenu(player.uniqueId, guild.id, policyService, guildProvider, lang).open(player)
    }
```
Add imports: `net.badgersmc.em.application.GuildTradePolicyService`, `net.badgersmc.em.domain.ports.GuildProvider`, `net.badgersmc.em.interaction.gui.GuildTradePolicyMenu` (and `org.bukkit.entity.Player` / `CommandSender` if not already imported).

- [ ] **Step 2: Build** — `./gradlew compileKotlin -Plumaguilds.jar=… --no-daemon --console=plain` → BUILD SUCCESSFUL. (If an `AdminCommandsTest` constructs `AdminCommands` directly, add the two `mockk(relaxed = true)` args.)
- [ ] **Step 3: Commit** — `feat(guild): /em guild policy opens the management GUI` + trailer.

---

## Task 6: Lang keys

**Files:** Modify `src/main/resources/lang/en_US.yml`

- [ ] **Step 1: Add keys** (MiniMessage; mirror existing `gui.*` colour style):
```yaml
guildpolicy:
  not_in_guild: "<red>You must be in a guild to manage trade policy.</red>"
  no_permission: "<red>You need the MANAGE_SHOPS guild permission for that.</red>"
gui:
  common:
    prev: "<gray>« Previous</gray>"
    next: "<gray>Next »</gray>"
    back: "<gray>« Back</gray>"
  guildpolicy:
    title: "<dark_red>Guild Trade Policy"
    add: "<green>+ Add a guild</green>"
    invalid: "<red><reason></red>"
    icon_tariff: "<gold><target></gold> <yellow>— tariff <red><rate>%</red></yellow>"
    icon_embargo: "<gold><target></gold> <red>— EMBARGOED</red>"
    icon_controls: "<gray>L +5% · R -5% · Shift-L embargo · Shift-R remove</gray>"
  guildpicker:
    title: "<dark_aqua>Pick a guild to target"
    entry: "<yellow><name></yellow>"
```
(If `gui.common.*` keys already exist with different wording, reuse them and drop the duplicates; check before adding.)

- [ ] **Step 2: Build** — `./gradlew processResources -Plumaguilds.jar=… --no-daemon --console=plain` → BUILD SUCCESSFUL (valid YAML, 2-space indent, no tabs).
- [ ] **Step 3: Commit** — `feat(lang): guild policy GUI messages` + trailer.

---

## Task 7: Final gate

- [ ] **Step 1:** `./gradlew clean detekt test shadowJar -Plumaguilds.jar=… --no-daemon --console=plain` → BUILD SUCCESSFUL, detekt 0, all tests pass, shadowJar built. If a menu `render`/`open` trips detekt `LongMethod`, it's pure IF wiring — `@Suppress("LongMethod")` on that method (matches `ShopEditMenu.render`).
- [ ] **Step 2: Report** — gate output + the 6 commit hashes. Do NOT push to BadgersMC; push the branch to `fork` only.

---

## Self-Review Notes (for the implementer)
1. **All mutations go through `GuildTradePolicyService`** — it already enforces MANAGE_SHOPS, the 99% cap, self-target, and the broadcast. The menus never write the repository directly.
2. **Pure logic is testable** — `stepUp`/`stepDown`/`selectable` are companion functions with unit tests; the IF rendering is construct-tested (mirror `ShopEditMenuTest`), not asserted pixel-by-pixel.
3. **Menu cross-navigation** — `GuildTradePolicyMenu` "+" opens `GuildPickerMenu`; the picker's entry/back reopen `GuildTradePolicyMenu`. Both share the same constructor deps; no shared state.
4. **`listGuilds` is the only new port method** — add it to every `GuildProvider` implementor (grep `: GuildProvider`); the LumaGuilds impl is a thin `getAllGuilds().map(toGuildRef)`.
5. **Scope** — this is the UI only; do not touch the engine (PR-A) or notifications (PR-C).
