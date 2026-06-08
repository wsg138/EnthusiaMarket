# Perf: Bulk Shop Ops — Implementation Plan (audit M-21)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Kill the N+1 JDBC round-trips in `ShopManagementService.trustAll` / `untrustAll` / `deleteAll`. Today each does `findByOwner` (1 query) and then a per-shop round-trip — `deleteAll` issues N `DELETE`s, and `trustAll`/`untrustAll` route through `mutateOwned` which **re-`findById`s every shop it already loaded** (N redundant reads + N writes). At scale (a player with many shops, run on the main thread) this stalls the server.

**Fix:**
- Add `ShopRepository.deleteByOwner(owner): Int` — one `DELETE … WHERE owner = ?`. `deleteAll` uses it (N deletes → 1) and fires `ShopDeletedEvent` from the already-fetched list.
- `trustAll`/`untrustAll` mutate the shops `findByOwner` already returned (drop the redundant per-id `findById`); only the necessary `upsert`s remain.

**Scope:** First of three sequential perf PRs (M-21 → M-20 → M-19); all touch `ShopRepository`, so they ship one at a time. This PR is `ShopManagementService` + `ShopRepository`(+Sql) only — **no schema change, no GUI**. Small PR.

**Architecture:** Hexagonal/SPEAR. Application service + a new repository port method. TDD.

**Tech Stack:** Kotlin 2.0.0, JDBC (`ShopRepositorySql`), JUnit 5 + MockK, detekt 1.23.8.

**Standing rules (every task):**
- Prefix bash with `cd <REPO> &&` (`/d/BadgersMC-Dev/EnthusiaMarket` or `/opt/data/EnthusiaMarket`). On Hermes' box also prefix gradle with `export JAVA_HOME=/opt/data/jdk-21.0.11+10 &&`.
- Every gradle command includes `-Plumaguilds.jar=<JAR> --no-daemon --console=plain`.
- LF→CRLF warnings expected. Branch `fix/perf-bulk-ops` off current `main`. Do not push (coordinator opens the PR).
- TDD. Commit after every task.
- Gate rule: compare each `Run:` to `Expected:`; mismatch → STOP, fix, re-run; HALT after 3 tries. Final gate on the EXACT committed HEAD.

---

## CONFIRMED API SYMBOLS (verified against the repo — use exactly)

- **`ShopRepository`** (`domain/shop/ShopRepository.kt`) — interface. Has `findByOwner(owner: UUID): List<Shop>`, `delete(id: Long)`, `upsert(shop: Shop): Shop`, `findById(id: Long): Shop?`. **Add `fun deleteByOwner(owner: UUID): Int`.** Implementors: `ShopRepositorySql` (production) and any in-memory test fake — **grep `: ShopRepository` and add the method to every implementor** (a fake can `removeAll { it.owner == owner }` and return the removed count).
- **`ShopRepositorySql`** (`infrastructure/persistence/ShopRepositorySql.kt`, ctor `(ds: DataSource)`) — pattern for a delete (mirror `delete`):
  ```kotlin
  override fun delete(id: Long) {
      ds.connection.use { conn ->
          conn.prepareStatement("DELETE FROM shop_items WHERE id = ?").use { ps ->
              ps.setLong(1, id); ps.executeUpdate()
          }
      }
  }
  ```
  `owner` is stored as a string column (`findByOwner` uses `WHERE owner = ?` with `owner.toString()`). `executeUpdate()` returns the affected-row count.
- **`ShopManagementService`** (`application/ShopManagementService.kt`, `@Service(shopRepository)`):
  - `fun shopsOwnedBy(owner): List<Shop> = shopRepository.findByOwner(owner)`.
  - `trustAll(actor, target) = trust(actor, target, shopsOwnedBy(actor).map { it.id })`; `trust(...)` → `mutateOwned(actor, shopIds) { it.copy(trusted = it.trusted + target) }`. `untrustAll` symmetric with `- target`.
  - `mutateOwned(actor, shopIds, edit)` does `for (id in shopIds) { val shop = findById(id) ?: continue; if (shop.owner != actor) continue; … upsert if changed }` — the redundant re-read. **Keep `mutateOwned` for the menu path** (`trust`/`untrust` with explicit ids from `BulkTrustMenu`); only the `*All` variants change.
  - `deleteAll(actor)` = `val owned = shopsOwnedBy(actor); owned.forEach { shopRepository.delete(it.id); fireShopDeleted(it.owner) }; return owned.size`.
  - `private fun fireShopDeleted(owner: UUID)` — null-safe `ShopDeletedEvent` fire (no-op in unit tests).
- **detekt** — keep the new private helper small; no `@Suppress` needed.

---

## Task 1: deleteByOwner on the repository

**Files:**
- Modify: `src/main/kotlin/net/badgersmc/em/domain/shop/ShopRepository.kt`
- Modify: `src/main/kotlin/net/badgersmc/em/infrastructure/persistence/ShopRepositorySql.kt`
- Modify: every other `ShopRepository` implementor (grep `: ShopRepository`)
- Test: the existing `ShopRepositorySql` test (round-trip), if present — else assert via the service test in Task 2.

- [ ] **Step 1: Add the port method + impl**

In `ShopRepository`, add `fun deleteByOwner(owner: UUID): Int`. In `ShopRepositorySql`:
```kotlin
    override fun deleteByOwner(owner: UUID): Int {
        ds.connection.use { conn ->
            conn.prepareStatement("DELETE FROM shop_items WHERE owner = ?").use { ps ->
                ps.setString(1, owner.toString())
                return ps.executeUpdate()
            }
        }
    }
```
Add the method to any other implementor (e.g. an in-memory fake → remove matching + return count).

- [ ] **Step 2: Build**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew compileKotlin compileTestKotlin -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: BUILD SUCCESSFUL (every implementor satisfies the new method).

- [ ] **Step 3: Commit**
```bash
cd /d/BadgersMC-Dev/EnthusiaMarket && git add src/main/kotlin/net/badgersmc/em/domain/shop/ShopRepository.kt src/main/kotlin/net/badgersmc/em/infrastructure/persistence/ShopRepositorySql.kt
# plus any other implementor files touched
git commit -m "feat(shop): ShopRepository.deleteByOwner — single bulk delete (M-21)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 2: ShopManagementService uses bulk delete + skips redundant reads

**Files:**
- Modify: `src/main/kotlin/net/badgersmc/em/application/ShopManagementService.kt`
- Test: `src/test/kotlin/net/badgersmc/em/application/ShopManagementServiceTest.kt`

- [ ] **Step 1: Failing tests**

Add (mirror the test's existing builder/mocks):
```kotlin
    @Test fun `deleteAll issues one bulk delete and fires an event per owned shop`() {
        // findByOwner(actor) returns 2 shops; deleteByOwner(actor) returns 2
        // assert: verify(exactly = 1) { repo.deleteByOwner(actor) }
        //         verify(exactly = 0) { repo.delete(any()) }
        //         result == 2
    }
    @Test fun `trustAll mutates owned shops without re-fetching them by id`() {
        // findByOwner(actor) returns 2 shops not already trusting target
        // assert: verify(exactly = 0) { repo.findById(any()) }
        //         verify(exactly = 2) { repo.upsert(match { it.trusted.contains(target) }) }
        //         result == 2
    }
```
Keep the existing `trust(actor, target, shopIds)` menu-path tests (they still use `mutateOwned` + `findById`).

- [ ] **Step 2: Run — verify RED**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew test --tests "*ShopManagementServiceTest" -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: FAIL — `deleteAll` still loops `delete`; `trustAll` still re-`findById`s.

- [ ] **Step 3: Implement**

```kotlin
    fun trustAll(actor: UUID, target: UUID): Int =
        mutateAll(shopsOwnedBy(actor)) { it.copy(trusted = it.trusted + target) }

    fun untrustAll(actor: UUID, target: UUID): Int =
        mutateAll(shopsOwnedBy(actor)) { it.copy(trusted = it.trusted - target) }

    fun deleteAll(actor: UUID): Int {
        val owned = shopsOwnedBy(actor)
        if (owned.isEmpty()) return 0
        val deleted = shopRepository.deleteByOwner(actor)
        owned.forEach { fireShopDeleted(it.owner) }
        return deleted
    }

    /** Apply [edit] to shops already known to belong to the actor (no re-fetch, no owner re-check). */
    private fun mutateAll(owned: List<Shop>, edit: (Shop) -> Shop): Int {
        var changed = 0
        for (shop in owned) {
            val updated = edit(shop)
            if (updated != shop) {
                shopRepository.upsert(updated)
                changed++
            }
        }
        return changed
    }
```
Leave `trust`/`untrust`(ids)/`mutateOwned`/`delete`/`adminDelete` unchanged.

- [ ] **Step 4: Run — verify GREEN**

Run: same as Step 2. Expected: PASS (new + existing menu-path tests).

- [ ] **Step 5: Commit**
```bash
cd /d/BadgersMC-Dev/EnthusiaMarket && git add src/main/kotlin/net/badgersmc/em/application/ShopManagementService.kt src/test/kotlin/net/badgersmc/em/application/ShopManagementServiceTest.kt
git commit -m "perf(shop): deleteAll bulk-deletes, trustAll/untrustAll skip re-reads (M-21)

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
1. **Behavior preserved** — `deleteAll` still fires one `ShopDeletedEvent` per owned shop (sign-cleanup listeners depend on it); it just deletes in one SQL instead of N. `trustAll`/`untrustAll` produce the same trusted sets, only without the redundant reads.
2. **`mutateOwned` stays** for the menu path (`trust`/`untrust` with explicit ids that were NOT pre-loaded and DO need an owner check). Don't delete it.
3. **Every `ShopRepository` implementor** must get `deleteByOwner` or the build breaks — grep `: ShopRepository`.
4. **No schema change** — `deleteByOwner` is a query on the existing `owner` column.
5. **Scope** — this is M-21 only. M-20 (search→SQL, schema migration) and M-19 (async GUI) are the next two PRs; do not start them here.
