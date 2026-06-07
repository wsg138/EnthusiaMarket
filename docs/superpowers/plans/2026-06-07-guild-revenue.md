# Guild Revenue Routing — Implementation Plan (audit M-15 + M-18)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Stop guild-owned value leaking into individual members' pockets. Two fixes:
- **M-15** — a BUY/SELL sign shop placed inside a GUILD-owned stall is created as a *personal* shop (`owner = placer`, `guildId = null`), so all container-trade revenue goes to the placer's personal vault instead of the guild bank. (TRADE shops in guild stalls are already rejected.)
- **M-18** — `SellOfferService.purchase` always awards `OwnerRef.solo(buyer)` and pays `offer.sellerUuid` personally; when a member sells the *guild's* stall the proceeds go to that member, not the guild bank.

**Design decisions (settled in brainstorming):**
- M-15: **auto-bind** the shop to the guild — set `Shop.guildId` at creation. The container-trade money path *already* routes to the guild bank whenever `shop.guildId != null` (`ContainerTradeService` lines 171/178/185), so no new routing is needed. The placer stays `owner` (for management/trust); only the money follows the guild.
- M-18: route the sale **proceeds** to the guild bank when the sold stall was GUILD-owned. The buyer still becomes the new SOLO owner (a normal transfer out of the guild); only where the money lands changes.
- Players are never in two guilds (server rule), so M-17 needs no fix and is dropped.

**Out of scope:** M-16 (guild-disband cleanup) is a separate PR. Nothing else from the audit. **Small PR.**

**Architecture:** Hexagonal/SPEAR. Both fixes are application/infrastructure-layer; no domain change. TDD both.

**Tech Stack:** Kotlin 2.0.0, Nexus DI, JUnit 5 + MockK + MockBukkit, detekt 1.23.8.

**Standing rules (every task):**
- Prefix bash with `cd <REPO> &&` (`/d/BadgersMC-Dev/EnthusiaMarket` or `/opt/data/EnthusiaMarket`). On Hermes' box also prefix gradle with `export JAVA_HOME=/opt/data/jdk-21.0.11+10 &&`.
- Every gradle command includes `-Plumaguilds.jar=<JAR> --no-daemon --console=plain`.
- LF→CRLF warnings expected. Branch `fix/guild-revenue` off current `main`. Do not push (coordinator opens the PR).
- TDD: failing test first, run RED, then GREEN. Commit after every task.
- Gate rule: compare each `Run:` to `Expected:`; mismatch → STOP, fix, re-run; HALT after 3 tries. Final gate on the EXACT committed HEAD.

---

## CONFIRMED API SYMBOLS (verified against the repo — use exactly)

- **`SellOfferService.purchase(stallId, buyer): Result`** (`application/SellOfferService.kt`). Ctor already injects `guildProvider: GuildProvider` (+ offers, stalls, auctions, economy, config, limits, ownership). Flow: load `offer` + `stall`; cap gate; `economy.withdraw(buyer, total)`; **step 1** `val updated = stall.awardTo(OwnerRef.solo(buyer), offer.price, now); stalls.save(updated); offers.delete(stallId)`; **step 2** `if (!economy.deposit(offer.sellerUuid, offer.price)) { log.warning(...) }` then tax routing. `stall` (the pre-transfer load) keeps the original owner — `awardTo` returns a copy, doesn't mutate `stall`. **No `OwnerType` import yet — add it.**
- **`GuildProvider.bankDeposit(guildId: String, amount: Long): Boolean`** — returns false on failure (doesn't throw for the normal case). `OwnerRef.type: OwnerType`, `OwnerRef.id: String` (for GUILD, `id` is the guild id string).
- **`SignPlaceListener.onSignPlace`** (`infrastructure/listeners/SignPlaceListener.kt`). Already injects `guildProvider`. Builds `val shop = Shop(stallId=…, owner=player.uniqueId, …, direction=direction, searchEnabled=config.shop.searchDefault)` then `shopRepository.upsert(shop)`. The enclosing `stall` (a `Stall`) is in scope (`val stall = findStallAt(block.location) ?: …`). TRADE+GUILD is already rejected earlier (`if (direction == SignDirection.TRADE && stall.owner.type == OwnerType.GUILD) …`). `java.util.UUID` is referenced in the file.
- **`Shop`** (`domain/shop/Shop.kt`) — data class with `val guildId: UUID? = null` (default null). Persisted by `ShopRepositorySql` upsert (writes `guild_id`, param 20). Setting it at construction is sufficient; no service call required.
- **`ContainerTradeService`** — money path already branches on `shop.guildId`: `if (guildId != null) guildProvider?.bankWithdraw(guildId.toString(), cost) else economy.withdraw(ownerUuid, cost)` (and the deposit/balance siblings). So a `guildId`-tagged shop routes to the guild bank automatically.
- **`OwnerType`** = `net.badgersmc.em.domain.stall.OwnerType` `{ NONE, SOLO, GUILD }`.

---

## Task 1: M-18 — sell-offer proceeds route to the guild bank

**Files:**
- Modify: `src/main/kotlin/net/badgersmc/em/application/SellOfferService.kt`
- Test: `src/test/kotlin/net/badgersmc/em/application/SellOfferServiceTest.kt`

- [ ] **Step 1: Failing test**

Read `SellOfferServiceTest` for its builder/mocks (mirror an existing `purchase` success test). Add a guild case:
```kotlin
    @Test fun `purchase of a guild-owned stall pays the guild bank, not the seller`() {
        // stall.owner.type == GUILD (owner.id = guildId); offer created by a member.
        // every { guildProvider.bankDeposit(guildId, price) } returns true
        // After purchase: verify(exactly = 1) { guildProvider.bankDeposit(guildId, price) }
        //                 verify(exactly = 0) { economy.deposit(sellerUuid, price) }
        // Buyer still becomes the SOLO owner (assert the saved stall.owner == OwnerRef.solo(buyer)).
    }
```
Use the project's guild owner-ref helper for the stall fixture (`OwnerRef.guild(guildId)`); keep an existing SOLO purchase test as the regression guard.

- [ ] **Step 2: Run — verify RED**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew test --tests "net.badgersmc.em.application.SellOfferServiceTest" -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: FAIL — current code calls `economy.deposit(seller, …)` regardless of owner type.

- [ ] **Step 3: Route proceeds by owner type**

Add `import net.badgersmc.em.domain.stall.OwnerType`. Before the awardTo (while `stall` still holds the original owner), capture:
```kotlin
        val sellerIsGuild = stall.owner.type == OwnerType.GUILD
        val proceedsGuildId = stall.owner.id // valid only when sellerIsGuild
```
Replace the step-2 seller deposit block with:
```kotlin
        val proceedsPaid = if (sellerIsGuild) {
            guildProvider.bankDeposit(proceedsGuildId, offer.price)
        } else {
            economy.deposit(offer.sellerUuid, offer.price)
        }
        if (!proceedsPaid) {
            log.warning(
                "SellOfferService.purchase: proceeds deposit failed for " +
                    (if (sellerIsGuild) "guild $proceedsGuildId" else "seller ${offer.sellerUuid}") +
                    " (price=${offer.price}); stall transfer already committed."
            )
        }
```
Leave the cap gate, withdraw, transfer (`OwnerRef.solo(buyer)` stays — buyer owns it personally now), tax routing, and events unchanged.

- [ ] **Step 4: Run — verify GREEN**

Run: same as Step 2. Expected: PASS (guild case + existing SOLO purchase tests).

- [ ] **Step 5: Commit**
```bash
cd /d/BadgersMC-Dev/EnthusiaMarket && git add src/main/kotlin/net/badgersmc/em/application/SellOfferService.kt src/test/kotlin/net/badgersmc/em/application/SellOfferServiceTest.kt
git commit -m "fix(offer): sell-offer proceeds on a guild stall pay the guild bank (M-18)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 2: M-15 — guild-stall sign shops bind to the guild

**Files:**
- Modify: `src/main/kotlin/net/badgersmc/em/infrastructure/listeners/SignPlaceListener.kt`
- Test: `src/test/kotlin/net/badgersmc/em/infrastructure/listeners/SignPlaceListenerTest.kt`

- [ ] **Step 1: Failing test**

Read `SignPlaceListenerTest` (MockBukkit) for its fixture (how it makes a stall + places a sign). Add:
```kotlin
    @Test fun `sign shop in a GUILD stall is created bound to the guild`() {
        // findStallAt returns a GUILD-owned stall (owner.type=GUILD, owner.id=guildId).
        // Place a [SELL] sign on a container; capture the upserted Shop.
        // assert shop.guildId == UUID.fromString(guildId) and shop.owner == placer.
    }
    @Test fun `sign shop in a SOLO stall has null guildId`() {
        // regression: SOLO stall → shop.guildId == null
    }
```
Capture the upserted shop via the mock (`val slot = slot<Shop>(); verify { shopRepository.upsert(capture(slot)) }`) or the test's existing fake repo.

- [ ] **Step 2: Run — verify RED**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew test --tests "*SignPlaceListenerTest" -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: FAIL — `guildId` is currently always null.

- [ ] **Step 3: Set guildId at construction**

Add `import net.badgersmc.em.domain.stall.OwnerType` if absent. In the `Shop(...)` constructor, add:
```kotlin
            guildId = if (stall.owner.type == OwnerType.GUILD) {
                runCatching { java.util.UUID.fromString(stall.owner.id) }.getOrNull()
            } else {
                null
            },
```
(A corrupt guild id falls back to null — a personal shop — rather than crashing sign placement.) Change nothing else; the placer stays `owner`.

- [ ] **Step 4: Run — verify GREEN**

Run: same as Step 2. Expected: PASS (guild + SOLO regression + existing placement tests).

- [ ] **Step 5: Commit**
```bash
cd /d/BadgersMC-Dev/EnthusiaMarket && git add src/main/kotlin/net/badgersmc/em/infrastructure/listeners/SignPlaceListener.kt src/test/kotlin/net/badgersmc/em/infrastructure/listeners/SignPlaceListenerTest.kt
git commit -m "fix(shop): sign shops in guild stalls bind to the guild bank (M-15)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 3: Final gate

- [ ] **Step 1: Full verification on committed HEAD**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew clean detekt test shadowJar -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: BUILD SUCCESSFUL, detekt 0, all tests pass, shadowJar built.

- [ ] **Step 2: Report** — gate output + the 2 commit hashes. Do NOT push.

---

## Self-Review Notes (for the implementer)
1. **M-15 needs no routing code** — `ContainerTradeService` already follows `shop.guildId`. Only set the field; do not add deposit logic to the listener.
2. **M-18 keeps the buyer SOLO** — only the *proceeds* destination changes. Read `stall.owner` BEFORE `awardTo` (it returns a copy; `stall` is unchanged, but capture the booleans up front for clarity).
3. **No domain change** — `Shop.guildId` and `bankDeposit` already exist; do not touch the domain layer or the DB schema.
4. **Corrupt guild id** → null (personal) shop in M-15; never throw from sign placement.
5. **Scope** — do NOT touch disband cleanup (M-16, separate PR) or the legacy `ShopTradeService`/`ShopSign` path.
