# Compensation Hardening — Implementation Plan (audit C-4, C-11, C-13, C-14)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax.

**Goal:** When a trade's *compensation* (rollback / payout) itself fails — a Vault deposit/withdraw or item give-back that the rollback path relies on returns false — money or items are silently stranded with only a `logger.warning`. No operator alert, no structured record. These are low-probability (they need a Vault op to fail mid-rollback) but they are the last money-integrity gaps. Audit findings:
- **C-14** `ShopTradeService.rollbackBuyWithdraw` — item give-back fails → item in limbo.
- **C-13** `ShopTradeService.rollbackBuyDeposit` — owner refund fails → owner out `price`.
- **C-11** `ShopTradeService.rollbackSellItem` — owner debit-back fails → owner keeps already-deposited proceeds (money creation).
- **C-4** `SellOfferService.purchase` — seller/guild proceeds deposit fails after the stall already transferred → payee unpaid.
- (Also `ShopTradeService.rollbackSellDeposit`, same class — covered for consistency.)

**Fix (settled):** add a single **operator-alert primitive** and call it at every terminal compensation-failure site. No control-flow change, no escrow/freeze (deferred — bigger design). Each site keeps its existing return value; we upgrade the `warning` to a **SEVERE log + a `TradeCompensationFailedEvent`** so staff can reconcile manually. Modeled on the existing `SchematicCaptureFailedEvent` operator-notification pattern.

**Parallelism note:** This is **one cohesive PR / one coordinator.** Three of the four findings live in the same file (`ShopTradeService.kt`) and all four share the new event + alert service — do NOT split across branches.

**Architecture:** Hexagonal/SPEAR. New `events/TradeCompensationFailedEvent` (domain-free Bukkit event) + `application/CompensationAlertService` (injectable, the only place that logs+fires). Injected into `ShopTradeService` and `SellOfferService`. TDD via the injected alerter (mockable) — avoids depending on a live Bukkit for assertions.

**Tech Stack:** Kotlin 2.0.0, Nexus DI (`@Service`), Bukkit events, JUnit 5 + MockK, detekt 1.23.8.

**Standing rules (every task):**
- Prefix bash with `cd <REPO> &&` (`/d/BadgersMC-Dev/EnthusiaMarket` or `/opt/data/EnthusiaMarket`). On Hermes' box also prefix gradle with `export JAVA_HOME=/opt/data/jdk-21.0.11+10 &&`.
- Every gradle command includes `-Plumaguilds.jar=<JAR> --no-daemon --console=plain`.
- LF→CRLF warnings expected. Branch `fix/compensation-hardening` off current `main`. Do not push (coordinator opens the PR).
- TDD where noted. Commit after every task.
- Gate rule: compare each `Run:` to `Expected:`; mismatch → STOP, fix, re-run; HALT after 3 tries. Final gate on the EXACT committed HEAD.

---

## CONFIRMED API SYMBOLS (verified against the repo — use exactly)

- **`SchematicCaptureFailedEvent`** (`events/`) — the shape to mirror: `class … (val stallId: String, …, val cause: Throwable) : Event()` with `override fun getHandlers() = handlerList` and `companion object { @JvmStatic val handlerList = HandlerList() }`. Fired via `Bukkit.getServer()?.pluginManager?.callEvent(...)`.
- **`ShopTradeService`** (`application/ShopTradeService.kt`, `@Service`) — ctor `(signRepository, stallRepository, economy: EconomyProvider, items: ItemProvider, config: EnthusiaMarketConfig)`; `private val logger`. Terminal compensation-failure sites, each currently `logger.warning(...)` then `return TradeResult.CompensationFailed(originalError, compensationError)`:
  - `rollbackBuyWithdraw` (C-14) — `items.giveItemToPlayer` failed; params `playerUuid, itemKey, amount, price`.
  - `rollbackBuyDeposit` (C-13) — owner refund / item return failed; params `playerUuid, ownerUuid, itemKey, amount, price`.
  - `rollbackSellDeposit` — player refund failed; params `playerUuid, price`.
  - `rollbackSellItem` (C-11) — player refund / owner debit failed; params `playerUuid, ownerUuid, sellerProceeds, price`.
- **`SellOfferService`** (`application/SellOfferService.kt`, `@Service`) — ctor `(offers, stalls, auctions, economy, config, guildProvider, limits, ownership)`; `private val log`. C-4 site: step 2 proceeds deposit — `val proceedsPaid = if (sellerIsGuild) guildProvider.bankDeposit(proceedsGuildId, offer.price) else economy.deposit(offer.sellerUuid, offer.price); if (!proceedsPaid) { log.warning("…proceeds deposit failed…") }`. (The earlier ownership-transfer `catch` is already SEVERE + rethrows — leave it.)
- **Null-safe event fire pattern** (used across the codebase): `try { Bukkit.getServer()?.pluginManager?.callEvent(event) } catch (e: Exception) { log.warning(...) }` — a listener failure must never break the trade path. In unit tests `Bukkit.getServer()` is null, so the fire is a safe no-op.
- **`@Service`** = `net.badgersmc.nexus.annotations.Service`. Existing service tests construct with `mockk(relaxed = true)` for new deps.

---

## Task 1: TradeCompensationFailedEvent + CompensationAlertService

**Files:**
- Create: `src/main/kotlin/net/badgersmc/em/events/TradeCompensationFailedEvent.kt`
- Create: `src/main/kotlin/net/badgersmc/em/application/CompensationAlertService.kt`
- Test: `src/test/kotlin/net/badgersmc/em/application/CompensationAlertServiceTest.kt`

- [ ] **Step 1: The event** (mirror `SchematicCaptureFailedEvent`)
```kotlin
package net.badgersmc.em.events

import java.util.UUID
import org.bukkit.event.Event
import org.bukkit.event.HandlerList

/**
 * Fired when a trade's compensation (rollback / payout) fails — money or items
 * could not be restored to a safe state. Purely an operator-reconciliation
 * signal (broadcast to staff, write to an audit channel); the trade has already
 * returned its failure result. [detail] carries a human-readable reconciliation
 * summary; [amount] is the value at risk.
 */
class TradeCompensationFailedEvent(
    val context: String,
    val detail: String,
    val affected: UUID?,
    val amount: Long,
) : Event() {
    override fun getHandlers(): HandlerList = handlerList
    companion object { @JvmStatic val handlerList = HandlerList() }
}
```

- [ ] **Step 2: Failing test for the alert service**
```kotlin
class CompensationAlertServiceTest {
    @Test fun `alert does not throw when Bukkit is unavailable`() {
        // In unit context Bukkit.getServer() is null; alert(...) must log + no-op the event fire, never throw.
        CompensationAlertService().alert("ctx", "detail", UUID.randomUUID(), 100L)
    }
}
```

- [ ] **Step 3: The alert service**
```kotlin
package net.badgersmc.em.application

import net.badgersmc.em.events.TradeCompensationFailedEvent
import net.badgersmc.nexus.annotations.Service
import org.bukkit.Bukkit
import java.util.UUID
import java.util.logging.Logger

/**
 * Single place that records a failed trade compensation: a SEVERE log line for
 * the server console plus a [TradeCompensationFailedEvent] for staff tooling.
 * Best-effort event fire — a listener failure must never propagate into a trade.
 */
@Service
class CompensationAlertService {
    private val log = Logger.getLogger(CompensationAlertService::class.java.name)

    fun alert(context: String, detail: String, affected: UUID?, amount: Long) {
        log.severe("COMPENSATION FAILED [$context]: $detail (affected=$affected, amount=$amount) — manual reconciliation required.")
        try {
            Bukkit.getServer()?.pluginManager?.callEvent(
                TradeCompensationFailedEvent(context, detail, affected, amount)
            )
        } catch (e: Exception) {
            log.warning("CompensationAlertService: failed to fire TradeCompensationFailedEvent for [$context]: ${e.message}")
        }
    }
}
```

- [ ] **Step 4: Run — RED→GREEN**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew test --tests "*CompensationAlertServiceTest" -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: RED before the service exists, GREEN after.

- [ ] **Step 5: Commit**
```bash
cd /d/BadgersMC-Dev/EnthusiaMarket && git add src/main/kotlin/net/badgersmc/em/events/TradeCompensationFailedEvent.kt src/main/kotlin/net/badgersmc/em/application/CompensationAlertService.kt src/test/kotlin/net/badgersmc/em/application/CompensationAlertServiceTest.kt
git commit -m "feat(trade): add TradeCompensationFailedEvent + CompensationAlertService

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 2: ShopTradeService — alert on every CompensationFailed (C-11, C-13, C-14)

**Files:**
- Modify: `src/main/kotlin/net/badgersmc/em/application/ShopTradeService.kt`
- Test: `src/test/kotlin/net/badgersmc/em/application/ShopTradeServiceTest.kt`

- [ ] **Step 1: Failing tests**

Read `ShopTradeServiceTest` for its builder. Add `mockk<CompensationAlertService>(relaxed = true)` as the new ctor dep. For each rollback path, drive the inner compensation to fail (e.g. for `rollbackSellItem`: `economy.deposit(player) ` succeeds initial but the give-item fails AND the refund `economy.deposit(player, price)` returns false) and assert:
```kotlin
    verify { alerter.alert(match { it.contains("rollbackSellItem") }, any(), any(), any()) }
```
Add one per site (buyWithdraw/C-14, buyDeposit/C-13, sellItem/C-11; sellDeposit too). Keep an existing happy-path test as the regression guard.

- [ ] **Step 2: Run — verify RED**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew test --tests "*ShopTradeServiceTest" -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: FAIL — alerter not injected / not called.

- [ ] **Step 3: Inject + call the alerter**

Add `private val alerter: CompensationAlertService` to the ctor. At each of the four sites, immediately before the existing `return TradeResult.CompensationFailed(...)`, replace the `logger.warning(...)` with a call:
```kotlin
            alerter.alert(
                context = "shop-trade:rollbackSellItem",
                detail = "give-item failed and compensation failed: ${failures.joinToString("; ")}",
                affected = ownerUuid,           // the party left out of pocket / over-credited
                amount = sellerProceeds,        // value at risk for this site
            )
```
Use the right `context` string (method name), the most-affected UUID, and the at-risk amount for each site (`price` for buyWithdraw/buyDeposit, `price` for sellDeposit, `sellerProceeds` for sellItem). Keep the `CompensationFailed` return unchanged.

- [ ] **Step 4: Run — verify GREEN**

Run: same as Step 2. Expected: PASS (new alert assertions + existing trade tests).

- [ ] **Step 5: Commit**
```bash
cd /d/BadgersMC-Dev/EnthusiaMarket && git add src/main/kotlin/net/badgersmc/em/application/ShopTradeService.kt src/test/kotlin/net/badgersmc/em/application/ShopTradeServiceTest.kt
git commit -m "fix(trade): alert operators on failed sign-trade compensation (C-11/C-13/C-14)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 3: SellOfferService — alert on proceeds-deposit failure (C-4)

**Files:**
- Modify: `src/main/kotlin/net/badgersmc/em/application/SellOfferService.kt`
- Test: `src/test/kotlin/net/badgersmc/em/application/SellOfferServiceTest.kt`

- [ ] **Step 1: Failing test**

Add `mockk<CompensationAlertService>(relaxed = true)` to the test builder. New case: a purchase where `economy.deposit(seller, price)` returns false → assert `verify { alerter.alert(match { it.contains("sell-offer") }, any(), offer.sellerUuid, offer.price) }`. (Add a guild variant: `guildProvider.bankDeposit(...)` returns false.) Keep an existing successful-purchase test green.

- [ ] **Step 2: Run — verify RED**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew test --tests "*SellOfferServiceTest" -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: FAIL.

- [ ] **Step 3: Inject + call the alerter**

Add `private val alerter: CompensationAlertService` to the ctor. In the `if (!proceedsPaid)` block (keep the existing severe/warning log), add:
```kotlin
            alerter.alert(
                context = "sell-offer:proceeds",
                detail = "stall ${stallId.value} transferred to buyer $buyer but proceeds payout failed " +
                    (if (sellerIsGuild) "to guild $proceedsGuildId" else "to seller ${offer.sellerUuid}"),
                affected = if (sellerIsGuild) null else offer.sellerUuid,
                amount = offer.price,
            )
```

- [ ] **Step 4: Run — verify GREEN**

Run: same as Step 2. Expected: PASS.

- [ ] **Step 5: Commit**
```bash
cd /d/BadgersMC-Dev/EnthusiaMarket && git add src/main/kotlin/net/badgersmc/em/application/SellOfferService.kt src/test/kotlin/net/badgersmc/em/application/SellOfferServiceTest.kt
git commit -m "fix(offer): alert operators when sell-offer proceeds payout fails (C-4)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 4: Final gate

- [ ] **Step 1: Full verification on committed HEAD**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew clean detekt test shadowJar -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: BUILD SUCCESSFUL, detekt 0, all tests pass, shadowJar built. If a touched method trips detekt complexity, extract the alert call doesn't add branches — re-check the test, not the production method.

- [ ] **Step 2: Report** — gate output + the 3 commit hashes. Do NOT push.

---

## Self-Review Notes (for the implementer)
1. **No control-flow change** — every site still returns its existing `CompensationFailed` / logs; you are ADDING an alert, not altering the outcome. Existing trade tests must stay green.
2. **The alert service is the only Bukkit-touching point** — services call `alerter.alert(...)`; they never fire the event directly. This keeps them unit-testable (mock the alerter).
3. **No escrow / freeze / retry** — explicitly out of scope (bigger design). The deliverable is a reliable operator signal, not auto-recovery.
4. **Pick the right `affected` UUID + `amount` per site** — it's the reconciliation hint; for guild proceeds (`sellerIsGuild`) pass `null` (no single player) and keep the guild id in `detail`.
5. **Ctor churn** — `ShopTradeService` and `SellOfferService` gain one dep; update their existing test constructors with `mockk(relaxed = true)`.
