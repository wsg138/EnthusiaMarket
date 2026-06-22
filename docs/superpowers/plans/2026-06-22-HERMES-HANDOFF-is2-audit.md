# HERMES HANDOFF — Periodic shop audit/repair (IS2-7 + IS2-8, REQ-294)

**Job:** `is2-audit`  **Base:** `origin/main`  **Branch:** `fix/is2-audit`
**Executor:** autonomous coordinator, TDD (failing test → RED → implement → GREEN).

You are in `/opt/data/EnthusiaMarket`. Implement the two tasks below **in order**. Touch ONLY the
files named here. This plan is self-contained — do not read other docs.

---

## Gradle invocation (use for EVERY gradle command)

```
export JAVA_HOME=/opt/data/jdk-21.0.11+10
./gradlew <tasks> -Plumaguilds.jar=/opt/data/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain
```

`java` is NOT on PATH — the `JAVA_HOME` export is mandatory.

---

## CONFIRMED API SYMBOLS (verified against origin/main — do not invent others)

- `net.badgersmc.em.domain.shop.ShopRepository` (interface):
  - `fun all(): List<Shop>`
  - `fun delete(id: Long)`
- `net.badgersmc.em.domain.shop.Shop` (data class): `val id: Long`, `val containerWorld: String`,
  `val containerX: Int`, `val containerY: Int`, `val containerZ: Int`. (No changes to Shop in this job.)
- Config class `net.badgersmc.em.config.EnthusiaMarketConfig` uses nested `class`es with
  `net.badgersmc.nexus.config.Comment` (`@Comment("…")`) annotations and `var field = default`.
  There is already a `class Shop` and a `class Rent` inside it — mirror that style.
- Scheduler idiom (CONFIRMED from `infrastructure/scheduler/RentScheduler.kt`): a class annotated
  `@net.badgersmc.nexus.annotations.Component` with an `@net.badgersmc.nexus.annotations.PostConstruct fun start()`
  that launches `object : org.bukkit.scheduler.BukkitRunnable() { override fun run() { … } }
  .runTaskTimer(plugin, intervalTicks, intervalTicks)`. Constructor-injects `org.bukkit.plugin.Plugin`,
  the service(s) it needs, and `EnthusiaMarketConfig`.
- **CRITICAL Nexus-DI gotcha:** Nexus DI is **lazy** — a `@Component`+`@PostConstruct` bean is NEVER
  constructed unless something eagerly resolves it. The existing schedulers are eagerly constructed in
  `EnthusiaMarket.onEnable` (grep `onEnable` for the block that resolves `RentScheduler`/`AuctionScheduler`
  via `ctx.getBean<…>()` — it is commented as eagerly constructing the schedulers). You MUST add a
  `ctx.getBean<ShopAuditService>()` line in that same eager-construct block, or the audit never runs.
- Container check (CONFIRMED Bukkit pattern): resolve `org.bukkit.Bukkit.getWorld(containerWorld)`
  (null ⇒ world not loaded), then `world.getBlockAt(x,y,z).state is org.bukkit.block.Container`.

---

## Task A — IS2-7: pure audit decision (TDD)  REQ-294

**File (impl):** `src/main/kotlin/net/badgersmc/em/application/ShopAuditDecision.kt`
**File (test):** `src/test/kotlin/net/badgersmc/em/application/ShopAuditDecisionTest.kt`

Pure application object — stdlib only, **no `org.bukkit` import** (the infra caller resolves world/block
state and passes booleans in). This keeps the "never delete a shop in an unloaded world" rule unit-tested.

```kotlin
package net.badgersmc.em.application

/** Pure audit decision for a single shop (REQ-294). Infra resolves world/block state; this decides. */
object ShopAuditDecision {
    enum class Decision { KEEP, REMOVE, SKIP }

    /**
     * @param worldLoaded     is the shop's container world currently loaded?
     * @param blockIsContainer when the world is loaded, is the container block still a Container?
     * SKIP when the world is unloaded (NEVER delete — we can't see the block). KEEP when the
     * container is present. REMOVE only when the world is loaded and the block is not a container.
     */
    fun evaluate(worldLoaded: Boolean, blockIsContainer: Boolean): Decision = TODO("IS2-7")
}
```

**Write the failing test FIRST** (kotlin.test, mirror `MaterialSuggestionsTest.kt` conventions —
`@Test`, `assertEquals`, same package so no import of the object):
- world unloaded ⇒ `SKIP` (even if blockIsContainer is false)
- world loaded + container present ⇒ `KEEP`
- world loaded + not a container ⇒ `REMOVE`

Run `./gradlew test --tests "net.badgersmc.em.application.ShopAuditDecisionTest"` → confirm RED
(`NotImplementedError`). Then implement: `if (!worldLoaded) SKIP else if (blockIsContainer) KEEP else REMOVE`.
Re-run → GREEN.

**Commit message (exact):**
```
feat(shop): pure ShopAuditDecision for periodic repair (IS2-7, REQ-294)

Decides KEEP/REMOVE/SKIP for one shop from world-loaded + block-is-container
booleans. SKIP on unloaded world so the sweeper never deletes shops it can't
see. Pure application object, stdlib only; red->green via ShopAuditDecisionTest.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
```

---

## Task B — IS2-8: scheduled audit/repair service (INFRA)  REQ-294

**File (new):** `src/main/kotlin/net/badgersmc/em/infrastructure/scheduler/ShopAuditScheduler.kt`
**File (edit):** `src/main/kotlin/net/badgersmc/em/config/EnthusiaMarketConfig.kt` — add a nested
  `class ShopAudit` (mirror the existing `class Rent`) with: `var enabled: Boolean = true`,
  `var intervalMinutes: Int = 10`, `var maxPerTick: Int = 5`, `var repairEnabled: Boolean = true`,
  each with a `@Comment`. Add `var shopAudit: ShopAudit = ShopAudit()` as a top-level field next to
  the existing `var rent`/`var shop` fields.
**File (edit):** `src/main/kotlin/net/badgersmc/em/EnthusiaMarket.kt` — add the eager-construct line
  (see CRITICAL gotcha) so the bean is built.

`ShopAuditScheduler` (mirror `RentScheduler` exactly):
- `@Component`, constructor: `(private val plugin: Plugin, private val shops: ShopRepository, private val config: EnthusiaMarketConfig)`.
- `@PostConstruct fun start()`: if `!config.shopAudit.enabled` return. `val intervalTicks = config.shopAudit.intervalMinutes.toLong() * 60L * 20L`. Launch a `BukkitRunnable` with `.runTaskTimer(plugin, intervalTicks, intervalTicks)`.
- In `run()`: iterate `shops.all()`, processing at most `config.shopAudit.maxPerTick` per invocation
  (keep a rolling cursor index across ticks so large shop counts are throttled, not all-at-once). For
  each shop: `val world = Bukkit.getWorld(shop.containerWorld)`; `val loaded = world != null`;
  `val isContainer = loaded && world!!.getBlockAt(shop.containerX, shop.containerY, shop.containerZ).state is Container`;
  `when (ShopAuditDecision.evaluate(loaded, isContainer)) { REMOVE -> if (config.shopAudit.repairEnabled) { shops.delete(shop.id); plugin.logger.info("[audit] removed orphaned shop ${shop.id} at ${shop.containerWorld} ${shop.containerX},${shop.containerY},${shop.containerZ}") }; else -> {} }`.
- Wrap the tick body in try/catch logging at SEVERE (mirror RentScheduler) so one bad shop never kills the timer.

No test is required for Task B (INFRA: scheduler wiring; the decision logic is covered by IS2-7). Just
confirm it compiles and the full gate passes.

**Commit message (exact):**
```
feat(shop): periodic shop audit/repair scheduler (IS2-8, REQ-294)

@Component/@PostConstruct BukkitRunnable on a config interval that scans shops
in throttled batches and removes orphaned ones (container gone) via
ShopAuditDecision. Eager-constructed in onEnable (Nexus DI is lazy). Config:
shop-audit enabled/interval-minutes/max-per-tick/repair-enabled.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
```

---

## Final gate (must pass before pushing)

```
export JAVA_HOME=/opt/data/jdk-21.0.11+10
./gradlew clean detekt test shadowJar -Plumaguilds.jar=/opt/data/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain
```

Must end `BUILD SUCCESSFUL`, detekt 0 issues, all tests passing. If detekt flags the new scheduler's
`run()` as too complex, extract a private `auditOne(shop)` helper.

When green, push to the **fork remote only**:
```
git push fork fix/is2-audit
```
NEVER push to `origin` or `upstream`. Finish by printing `IS2-AUDIT_DONE` then the commit short-hashes
and the final `BUILD SUCCESSFUL` line. If any gate fails 3 times, stop and print `IS2-AUDIT_HALT` with
the failing command and error.
