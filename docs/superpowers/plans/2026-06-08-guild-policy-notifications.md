# Guild Policy Notifications — Implementation Plan (PR-C)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Make guild tariffs/embargoes visible: a **server broadcast** when a policy is set/changed/lifted, and a **personal warning** when a player enters a guild stall where their guild is tariffed/embargoed.

**Architecture:** The engine (PR-A, merged) stays Bukkit-broadcast-free. The three management methods fire a new `GuildTradePolicyChangedEvent`; an announce listener broadcasts it. A `PlayerMoveEvent` listener warns on region entry, using a new direction-free `policyToward` lookup. Both listeners are `@Component`-autoregistered, config-toggleable, and put their logic-heavy decisions in testable seams.

**Tech Stack:** Kotlin 2.0.0, Nexus DI + `LangService` (`net.badgersmc.nexus.i18n.LangService`), Bukkit events, JUnit 5 + MockK, detekt 1.23.8.

**Reference spec:** `docs/superpowers/specs/2026-06-08-guild-policy-notifications-design.md`

**Standing rules (every task):**
- Prefix bash with `cd <REPO> &&`. On Hermes' box prefix gradle with `export JAVA_HOME=/opt/data/jdk-21.0.11+10 &&`; repo `/opt/data/EnthusiaMarket`, jar `/opt/data/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar`.
- Every gradle command includes `-Plumaguilds.jar=<JAR> --no-daemon --console=plain`.
- LF→CRLF warnings expected. Branch `feat/guild-policy-notifications` off current `main`. Do not push to BadgersMC (coordinator opens the PR; Hermes pushes to `fork` only).
- TDD where noted. Commit after every task.
- Gate rule: compare each `Run:` to `Expected:`; mismatch → STOP, fix, re-run; HALT after 3 tries.

---

## CONFIRMED API SYMBOLS (verified against the repo)

- **`GuildTradePolicyService`** (`application/`, `@Service`, ctor `(policies: GuildTradePolicyRepository, guildProvider: GuildProvider)`): `setTariff/setEmbargo/clear` each return `mutate(actor, owner, target) { … policies.upsert/delete(…); PolicyResult.Ok }`. `mutate` already gates self-target + MANAGE_SHOPS. `stanceFor(...)` exists. `companion { const val MAX_TARIFF_PCT = 99 }`. **No logger yet — add one.**
- **`GuildTradePolicy`** (`domain/guild/`): `(ownerGuildId, targetGuildId, kind: PolicyKind, ratePct)`. `PolicyKind { TARIFF, EMBARGO }`.
- **`GuildTradePolicyRepository.find(owner, target): GuildTradePolicy?`**.
- **`GuildProvider`** (`domain/ports/`): `guildOf(player: UUID): GuildRef?`, `guildById(id: String): GuildRef?`, `GuildRef(id: String, name: String, …)`.
- **`RegionProvider.regionAt(world: String, x: Int, y: Int, z: Int): String?`** (`domain/ports/`).
- **`StallRepository.findByRegion(world: String, regionId: String): Stall?`**. `Stall.owner: OwnerRef` (`type: OwnerType{NONE,SOLO,GUILD}`, `id: String`).
- **`LangService`** = `net.badgersmc.nexus.i18n.LangService`; `lang.msg(key: String, vararg pairs: Pair<String, Any>): net.kyori.adventure.text.Component`. Usage: `player.sendMessage(lang.msg("key", "tok" to v))`. Tokens render via `<tok>` in `en_US.yml`.
- **Broadcast:** Paper `org.bukkit.Bukkit.broadcast(component: net.kyori.adventure.text.Component)`.
- **Event pattern:** mirror `events/SchematicCaptureFailedEvent` — `class X(...) : org.bukkit.event.Event() { override fun getHandlers() = handlerList; companion object { @JvmStatic val handlerList = HandlerList() } }`. Fire via null-safe `Bukkit.getServer()?.pluginManager?.callEvent(...)` (no-op in unit tests).
- **`@Component`** = `net.badgersmc.nexus.annotations.Component`. `@Component` Bukkit `Listener`s are auto-registered by Phase-6 `registerNexusListeners` (no manual wiring). `@EventHandler` = `org.bukkit.event.EventHandler`.
- **Config:** `EnthusiaMarketConfig` top-level holds `var rent: Rent = Rent()` etc.; sub-classes are plain `class Foo { @Comment("…") var x: T = default }` (`@Comment` = `net.badgersmc.nexus.config.Comment` — copy the import from an existing sub-class like `Particles`). Add a `GuildPolicy` block + `var guildPolicy`.
- **Tests:** to assert event firing through the null-safe Bukkit path, mock the static (`mockkStatic(Bukkit::class)`, stub `Bukkit.getServer()` → a relaxed `Server` whose `pluginManager` is a relaxed mock) and `verify { pluginManager.callEvent(any()) }` — mirror `SellOfferServiceTest`'s `@BeforeTest mockBukkit`.

---

## Task 1: GuildTradePolicyChangedEvent + fire it + policyToward

**Files:**
- Create: `src/main/kotlin/net/badgersmc/em/events/GuildTradePolicyChangedEvent.kt`
- Modify: `src/main/kotlin/net/badgersmc/em/application/GuildTradePolicyService.kt`
- Test: `src/test/kotlin/net/badgersmc/em/application/GuildTradePolicyServiceTest.kt`

- [ ] **Step 1: The event**
```kotlin
package net.badgersmc.em.events

import net.badgersmc.em.domain.guild.PolicyKind
import org.bukkit.event.Event
import org.bukkit.event.HandlerList

/**
 * Fired when a guild's trade policy toward another guild is set, changed, or
 * cleared. Listeners broadcast it; the policy write itself is already done.
 * [kind] is null when [action] == CLEARED.
 */
class GuildTradePolicyChangedEvent(
    val ownerGuildId: String,
    val targetGuildId: String,
    val kind: PolicyKind?,
    val ratePct: Int,
    val action: Action,
) : Event() {
    enum class Action { SET, CLEARED }
    override fun getHandlers(): HandlerList = handlerList
    companion object { @JvmStatic val handlerList = HandlerList() }
}
```

- [ ] **Step 2: Failing tests** (append to `GuildTradePolicyServiceTest`; this file already has the `gp()` mock helper + `buyer`)
```kotlin
    @Test fun `setTariff fires a SET event`() {
        val repo = mockk<GuildTradePolicyRepository>(relaxed = true)
        val gpm = mockk<GuildProvider>(relaxed = true); every { gpm.hasShopPermission(any(), any(), any()) } returns true
        GuildTradePolicyService(repo, gpm).setTariff(buyer, "g1", "g2", 20)
        io.mockk.verify { pluginManager.callEvent(match<net.badgersmc.em.events.GuildTradePolicyChangedEvent> {
            it.action == net.badgersmc.em.events.GuildTradePolicyChangedEvent.Action.SET &&
                it.kind == PolicyKind.TARIFF && it.ratePct == 20 && it.ownerGuildId == "g1" && it.targetGuildId == "g2"
        }) }
    }
    @Test fun `clear fires a CLEARED event`() {
        val repo = mockk<GuildTradePolicyRepository>(relaxed = true)
        val gpm = mockk<GuildProvider>(relaxed = true); every { gpm.hasShopPermission(any(), any(), any()) } returns true
        GuildTradePolicyService(repo, gpm).clear(buyer, "g1", "g2")
        io.mockk.verify { pluginManager.callEvent(match<net.badgersmc.em.events.GuildTradePolicyChangedEvent> {
            it.action == net.badgersmc.em.events.GuildTradePolicyChangedEvent.Action.CLEARED
        }) }
    }
    @Test fun `denied setTariff fires no event`() {
        val gpm = mockk<GuildProvider>(relaxed = true); every { gpm.hasShopPermission(any(), any(), any()) } returns false
        GuildTradePolicyService(mockk(relaxed = true), gpm).setTariff(buyer, "g1", "g2", 20)
        io.mockk.verify(exactly = 0) { pluginManager.callEvent(any()) }
    }
    @Test fun `policyToward returns null for solo and own guild and the policy otherwise`() {
        val repo = mockk<GuildTradePolicyRepository> { every { find("g1", "g2") } returns GuildTradePolicy("g1","g2",PolicyKind.TARIFF,15) }
        assertEquals(15, svc(repo, gp("g2")).policyToward("g1", buyer)!!.ratePct)   // other guild → policy
        assertEquals(null, svc(repo, gp("g1")).policyToward("g1", buyer))            // own guild → null
        assertEquals(null, svc(mockk(relaxed = true), gp(null)).policyToward("g1", buyer)) // solo → null
    }
```
Add a `@BeforeTest`/`@AfterTest` Bukkit mock + a `pluginManager` field to the test class (mirror `SellOfferServiceTest.mockBukkit`):
```kotlin
    private lateinit var pluginManager: org.bukkit.plugin.PluginManager
    @kotlin.test.BeforeTest fun mockBukkit() {
        io.mockk.mockkStatic(org.bukkit.Bukkit::class)
        val server = mockk<org.bukkit.Server>(relaxed = true)
        pluginManager = mockk(relaxed = true)
        every { org.bukkit.Bukkit.getServer() } returns server
        every { server.pluginManager } returns pluginManager
    }
    @kotlin.test.AfterTest fun unmockBukkit() = io.mockk.unmockkStatic(org.bukkit.Bukkit::class)
```

- [ ] **Step 3: RED** — `./gradlew test --tests "*GuildTradePolicyServiceTest" -Plumaguilds.jar=… --no-daemon --console=plain` → new tests FAIL.

- [ ] **Step 4: Implement** — add a logger, a `fireChanged` helper, the event-fire in the three methods, and `policyToward`:
```kotlin
    // field near the top of the class:
    private val log = java.util.logging.Logger.getLogger(GuildTradePolicyService::class.java.name)
```
In `setTariff`, before `PolicyResult.Ok`:
```kotlin
            policies.upsert(GuildTradePolicy(ownerGuildId, targetGuildId, PolicyKind.TARIFF, ratePct))
            fireChanged(ownerGuildId, targetGuildId, PolicyKind.TARIFF, ratePct, GuildTradePolicyChangedEvent.Action.SET)
            PolicyResult.Ok
```
In `setEmbargo`:
```kotlin
            policies.upsert(GuildTradePolicy(ownerGuildId, targetGuildId, PolicyKind.EMBARGO, 0))
            fireChanged(ownerGuildId, targetGuildId, PolicyKind.EMBARGO, 0, GuildTradePolicyChangedEvent.Action.SET)
            PolicyResult.Ok
```
In `clear`:
```kotlin
            policies.delete(ownerGuildId, targetGuildId)
            fireChanged(ownerGuildId, targetGuildId, null, 0, GuildTradePolicyChangedEvent.Action.CLEARED)
            PolicyResult.Ok
```
Add the helpers (import `net.badgersmc.em.events.GuildTradePolicyChangedEvent`):
```kotlin
    /** A guild's policy toward [buyer]'s guild, ignoring trade direction. Null for solo / own-guild / no policy. */
    fun policyToward(ownerGuildId: String, buyer: UUID): GuildTradePolicy? {
        val buyerGuild = guildProvider.guildOf(buyer)?.id ?: return null
        if (buyerGuild == ownerGuildId) return null
        return policies.find(ownerGuildId, buyerGuild)
    }

    private fun fireChanged(owner: String, target: String, kind: PolicyKind?, rate: Int, action: GuildTradePolicyChangedEvent.Action) {
        try {
            org.bukkit.Bukkit.getServer()?.pluginManager?.callEvent(
                GuildTradePolicyChangedEvent(owner, target, kind, rate, action)
            )
        } catch (e: Exception) {
            log.warning("GuildTradePolicyService: failed to fire policy-changed event: ${e.message}")
        }
    }
```

- [ ] **Step 5: GREEN** — same command → PASS (new + existing).
- [ ] **Step 6: Commit** — `feat(guild): GuildTradePolicyChangedEvent + policyToward lookup` + Co-Authored-By trailer.

---

## Task 2: Config — guildPolicy block

**Files:** Modify `src/main/kotlin/net/badgersmc/em/config/EnthusiaMarketConfig.kt`

- [ ] **Step 1: Add the field + class.** Add a top-level field next to the others:
```kotlin
    @Comment("Guild tariff/embargo player notifications")
    var guildPolicy: GuildPolicy = GuildPolicy()
```
And a sub-class (mirror the `Particles` class style + its `@Comment` import):
```kotlin
    class GuildPolicy {
        @Comment("Broadcast to the whole server when a guild sets/changes/lifts a tariff or embargo.")
        var announceEnabled: Boolean = true
        @Comment("Warn a player on entering a guild stall where their guild is tariffed/embargoed.")
        var entryWarningEnabled: Boolean = true
        @Comment("Minimum seconds between a guild's policy-change broadcasts (anti-spam).")
        var announceCooldownSeconds: Int = 30
    }
```

- [ ] **Step 2: Build** — `./gradlew compileKotlin -Plumaguilds.jar=… --no-daemon --console=plain` → BUILD SUCCESSFUL.
- [ ] **Step 3: Commit** — `feat(config): guildPolicy notification toggles` + trailer.

---

## Task 3: GuildPolicyAnnounceListener (server broadcast)

**Files:**
- Create: `src/main/kotlin/net/badgersmc/em/infrastructure/listeners/GuildPolicyAnnounceListener.kt`
- Test: `src/test/kotlin/net/badgersmc/em/infrastructure/listeners/GuildPolicyAnnounceListenerTest.kt`

The Bukkit glue (`@EventHandler` → `Bukkit.broadcast`) is thin; the testable seams are `buildMessage` (picks the right lang key) and `onCooldown`.

- [ ] **Step 1: Failing tests**
```kotlin
package net.badgersmc.em.infrastructure.listeners

import io.mockk.every
import io.mockk.mockk
import net.badgersmc.em.config.EnthusiaMarketConfig
import net.badgersmc.em.domain.guild.PolicyKind
import net.badgersmc.em.domain.ports.GuildProvider
import net.badgersmc.em.events.GuildTradePolicyChangedEvent
import net.badgersmc.nexus.i18n.LangService
import net.kyori.adventure.text.Component
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class GuildPolicyAnnounceListenerTest {
    private fun listener(lang: LangService, gp: GuildProvider, cfg: EnthusiaMarketConfig = EnthusiaMarketConfig()) =
        GuildPolicyAnnounceListener(gp, lang, cfg)

    private fun lang(): LangService = mockk {
        every { msg(any(), *anyVararg()) } answers { Component.text(firstArg<String>()) } // echo the key for assertion
    }
    private fun gp(): GuildProvider = mockk {
        every { guildById("g1") } returns GuildProvider.GuildRef("g1", "Alpha")
        every { guildById("g2") } returns GuildProvider.GuildRef("g2", "Beta")
    }

    @Test fun `tariff set builds the tariff announce`() {
        val c = listener(lang(), gp()).buildMessage(GuildTradePolicyChangedEvent("g1","g2",PolicyKind.TARIFF,20,GuildTradePolicyChangedEvent.Action.SET))
        assertEquals("guildpolicy.announce.tariff", (c as Component).let { net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(it) })
    }
    @Test fun `embargo set builds the embargo announce`() {
        val c = listener(lang(), gp()).buildMessage(GuildTradePolicyChangedEvent("g1","g2",PolicyKind.EMBARGO,0,GuildTradePolicyChangedEvent.Action.SET))!!
        assertTrue(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(c).contains("embargo"))
    }
    @Test fun `cleared builds the cleared announce`() {
        val c = listener(lang(), gp()).buildMessage(GuildTradePolicyChangedEvent("g1","g2",null,0,GuildTradePolicyChangedEvent.Action.CLEARED))!!
        assertTrue(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(c).contains("cleared"))
    }
    @Test fun `cooldown suppresses a second announce within the window`() {
        val l = listener(lang(), gp())
        assertFalse(l.onCooldown("g1", now = 1_000_000L))           // first → allowed (records)
        assertTrue(l.onCooldown("g1", now = 1_000_000L + 5_000L))   // 5s later, default 30s window → suppressed
        assertFalse(l.onCooldown("g1", now = 1_000_000L + 40_000L)) // 40s later → allowed again
    }
}
```

- [ ] **Step 2: RED** — `./gradlew test --tests "*GuildPolicyAnnounceListenerTest" …` → FAIL (class missing).

- [ ] **Step 3: Implement**
```kotlin
package net.badgersmc.em.infrastructure.listeners

import net.badgersmc.em.config.EnthusiaMarketConfig
import net.badgersmc.em.domain.guild.PolicyKind
import net.badgersmc.em.domain.ports.GuildProvider
import net.badgersmc.em.events.GuildTradePolicyChangedEvent
import net.badgersmc.nexus.annotations.Component
import net.badgersmc.nexus.i18n.LangService
import net.kyori.adventure.text.Component as TextComponent
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener

@Component
class GuildPolicyAnnounceListener(
    private val guildProvider: GuildProvider,
    private val lang: LangService,
    private val config: EnthusiaMarketConfig,
) : Listener {
    private val lastAnnounce = mutableMapOf<String, Long>()

    @EventHandler
    fun onPolicyChanged(event: GuildTradePolicyChangedEvent) {
        if (!config.guildPolicy.announceEnabled) return
        if (onCooldown(event.ownerGuildId)) return
        buildMessage(event)?.let { Bukkit.broadcast(it) }
    }

    internal fun onCooldown(key: String, now: Long = System.currentTimeMillis()): Boolean {
        val window = config.guildPolicy.announceCooldownSeconds * 1000L
        val last = lastAnnounce[key]
        if (last != null && now - last < window) return true
        lastAnnounce[key] = now
        return false
    }

    internal fun buildMessage(event: GuildTradePolicyChangedEvent): TextComponent? {
        val owner = guildProvider.guildById(event.ownerGuildId)?.name ?: event.ownerGuildId
        val target = guildProvider.guildById(event.targetGuildId)?.name ?: event.targetGuildId
        return when {
            event.action == GuildTradePolicyChangedEvent.Action.CLEARED ->
                lang.msg("guildpolicy.announce.cleared", "owner" to owner, "target" to target)
            event.kind == PolicyKind.EMBARGO ->
                lang.msg("guildpolicy.announce.embargo", "owner" to owner, "target" to target)
            event.kind == PolicyKind.TARIFF ->
                lang.msg("guildpolicy.announce.tariff", "owner" to owner, "target" to target, "rate" to event.ratePct)
            else -> null
        }
    }
}
```

- [ ] **Step 4: GREEN** — same command → PASS.
- [ ] **Step 5: Commit** — `feat(guild): broadcast tariff/embargo changes server-wide` + trailer.

---

## Task 4: GuildShopPolicyEntryListener (entry warning)

**Files:**
- Create: `src/main/kotlin/net/badgersmc/em/infrastructure/listeners/GuildShopPolicyEntryListener.kt`
- Test: `src/test/kotlin/net/badgersmc/em/infrastructure/listeners/GuildShopPolicyEntryListenerTest.kt`

Testable seam: `warningFor(world, region, playerUuid): Component?`. The `onMove` glue (block-change guard + region tracking) is thin and build-verified.

- [ ] **Step 1: Failing tests** (drive `warningFor` with mocked deps; build a GUILD stall + a SOLO stall)
```kotlin
package net.badgersmc.em.infrastructure.listeners

import io.mockk.every
import io.mockk.mockk
import net.badgersmc.em.application.GuildTradePolicyService
import net.badgersmc.em.config.EnthusiaMarketConfig
import net.badgersmc.em.domain.guild.GuildTradePolicy
import net.badgersmc.em.domain.guild.PolicyKind
import net.badgersmc.em.domain.ports.GuildProvider
import net.badgersmc.em.domain.ports.RegionProvider
import net.badgersmc.em.domain.stall.OwnerRef
import net.badgersmc.em.domain.stall.RentTerms
import net.badgersmc.em.domain.stall.Stall
import net.badgersmc.em.domain.stall.StallId
import net.badgersmc.em.domain.stall.StallRepository
import net.badgersmc.em.domain.stall.StallState
import net.badgersmc.nexus.i18n.LangService
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GuildShopPolicyEntryListenerTest {
    private val player = UUID.randomUUID()
    private fun plain(c: Component) = PlainTextComponentSerializer.plainText().serialize(c)

    private fun guildStall() = Stall(
        id = StallId("s1"), regionId = "s1", world = "world", state = StallState.OWNED,
        owner = OwnerRef.guild("g1"), ownerSince = Instant.now(), winningBid = 100L, rentTerms = RentTerms.flat(1L),
    )
    private fun soloStall() = guildStall().copy(owner = OwnerRef.solo(UUID.randomUUID()))

    private fun lang(): LangService = mockk { every { msg(any(), *anyVararg()) } answers { Component.text(firstArg<String>()) } }
    private fun build(stall: Stall?, policy: GuildTradePolicy?): GuildShopPolicyEntryListener {
        val regions = mockk<RegionProvider>(relaxed = true)
        val stalls = mockk<StallRepository> { every { findByRegion("world", "s1") } returns stall }
        val policySvc = mockk<GuildTradePolicyService> { every { policyToward("g1", player) } returns policy }
        val gp = mockk<GuildProvider> { every { guildById("g1") } returns GuildProvider.GuildRef("g1", "Alpha") }
        return GuildShopPolicyEntryListener(regions, stalls, policySvc, gp, lang(), EnthusiaMarketConfig())
    }

    @Test fun `tariff at a guild stall warns`() {
        val c = build(guildStall(), GuildTradePolicy("g1","g2",PolicyKind.TARIFF,20)).warningFor("world", "s1", player)!!
        assertTrue(plain(c).contains("tariff"))
    }
    @Test fun `embargo warns`() {
        val c = build(guildStall(), GuildTradePolicy("g1","g2",PolicyKind.EMBARGO,0)).warningFor("world", "s1", player)!!
        assertTrue(plain(c).contains("embargo"))
    }
    @Test fun `no policy is silent`() { assertNull(build(guildStall(), null).warningFor("world", "s1", player)) }
    @Test fun `solo stall is silent`() { assertNull(build(soloStall(), null).warningFor("world", "s1", player)) }
    @Test fun `unknown region is silent`() { assertNull(build(null, null).warningFor("world", "s1", player)) }
}
```

- [ ] **Step 2: RED** — `./gradlew test --tests "*GuildShopPolicyEntryListenerTest" …` → FAIL.

- [ ] **Step 3: Implement**
```kotlin
package net.badgersmc.em.infrastructure.listeners

import net.badgersmc.em.application.GuildTradePolicyService
import net.badgersmc.em.config.EnthusiaMarketConfig
import net.badgersmc.em.domain.guild.PolicyKind
import net.badgersmc.em.domain.ports.GuildProvider
import net.badgersmc.em.domain.ports.RegionProvider
import net.badgersmc.em.domain.stall.OwnerType
import net.badgersmc.em.domain.stall.StallRepository
import net.badgersmc.nexus.annotations.Component
import net.badgersmc.nexus.i18n.LangService
import net.kyori.adventure.text.Component as TextComponent
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import java.util.UUID

@Component
class GuildShopPolicyEntryListener(
    private val regions: RegionProvider,
    private val stalls: StallRepository,
    private val policyService: GuildTradePolicyService,
    private val guildProvider: GuildProvider,
    private val lang: LangService,
    private val config: EnthusiaMarketConfig,
) : Listener {
    private val lastRegion = mutableMapOf<UUID, String?>()

    @EventHandler(ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) {
        val from = event.from
        val to = event.to
        if (from.blockX == to.blockX && from.blockY == to.blockY && from.blockZ == to.blockZ) return
        if (!config.guildPolicy.entryWarningEnabled) return
        val uuid = event.player.uniqueId
        val region = regions.regionAt(to.world.name, to.blockX, to.blockY, to.blockZ)
        if (region == lastRegion[uuid]) return
        lastRegion[uuid] = region
        if (region == null) return
        warningFor(to.world.name, region, uuid)?.let { event.player.sendMessage(it) }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) { lastRegion.remove(event.player.uniqueId) }

    /** The warning to show a player standing in [region], or null if none applies. */
    internal fun warningFor(world: String, region: String, playerUuid: UUID): TextComponent? {
        val stall = stalls.findByRegion(world, region) ?: return null
        if (stall.owner.type != OwnerType.GUILD) return null
        val policy = policyService.policyToward(stall.owner.id, playerUuid) ?: return null
        val owner = guildProvider.guildById(stall.owner.id)?.name ?: stall.owner.id
        return when (policy.kind) {
            PolicyKind.TARIFF -> lang.msg("guildpolicy.entry.tariff", "owner" to owner, "rate" to policy.ratePct)
            PolicyKind.EMBARGO -> lang.msg("guildpolicy.entry.embargo", "owner" to owner)
        }
    }
}
```

- [ ] **Step 4: GREEN** — same command → PASS.
- [ ] **Step 5: Commit** — `feat(guild): warn players entering a tariffed/embargoed guild stall` + trailer.

---

## Task 5: Lang keys

**Files:** Modify `src/main/resources/lang/en_US.yml`

- [ ] **Step 1: Add the keys** (find the existing top-level structure; add a `guildpolicy:` block. MiniMessage `<token>` syntax, matching other keys' colour style):
```yaml
guildpolicy:
  announce:
    tariff: "<gold>📢 <yellow><owner></yellow> has imposed a <red><rate>%</red> tariff on <yellow><target></yellow>."
    embargo: "<gold>📢 <yellow><owner></yellow> has <red>embargoed</red> <yellow><target></yellow>."
    cleared: "<gold>📢 <yellow><owner></yellow> has lifted its trade policy on <yellow><target></yellow>."
  entry:
    tariff: "<gold>⚠ Your guild is tariffed <red><rate>%</red> at <yellow><owner></yellow>'s shops here.</gold>"
    embargo: "<red>⛔ Your guild is embargoed — you cannot trade at <yellow><owner></yellow>'s shops here.</red>"
```

- [ ] **Step 2: Build (resources processed)** — `./gradlew processResources -Plumaguilds.jar=… --no-daemon --console=plain` → BUILD SUCCESSFUL (and confirm the YAML parses — no tabs, 2-space indent).
- [ ] **Step 3: Commit** — `feat(lang): guild policy notification messages` + trailer.

---

## Task 6: Final gate

- [ ] **Step 1:** `./gradlew clean detekt test shadowJar -Plumaguilds.jar=… --no-daemon --console=plain` → BUILD SUCCESSFUL, detekt 0, all tests pass, shadowJar built. If `onMove` trips a detekt complexity rule, the decision logic already lives in `warningFor`; keep `onMove` as the flat guard chain shown.
- [ ] **Step 2: Report** — gate output + the 5 commit hashes. Do NOT push to BadgersMC; push the branch to `fork` only.

---

## Self-Review Notes (for the implementer)
1. **The engine stays broadcast-free** — the service only *fires* the event; the announce listener does the broadcasting. Don't `Bukkit.broadcast` from the service.
2. **Logic in the testable seams** — `buildMessage`/`onCooldown`/`warningFor` are `internal` and unit-tested; the `@EventHandler` methods are thin glue (build-verified). Don't move logic back into the handlers.
3. **Hot path discipline** — `onMove` returns on same-block moves *before* any region/DB lookup, and only calls `regionAt`/`findByRegion` on a real region change.
4. **Silent cases** — solo buyer, own-guild member, no policy, and unknown region all return null (no message). The solo/own-guild branch lives in `policyToward` (Task 1).
5. **Config gates** — both listeners early-return when their toggle is false; the event still fires (for tests / other listeners).
