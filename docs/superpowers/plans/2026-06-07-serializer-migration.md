# ItemStack Serializer Migration — Implementation Plan (audit M-8)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Stop silent item-data loss. `ItemStackSerializer` and `ContainerTradeService.deserializeStack` persist/read shop items via the legacy `BukkitObjectOutputStream`/`BukkitObjectInputStream` (Java serialization). On Paper 1.20.5+ / 1.21.x this drops item **data components** (custom names, enchants, trim, etc.) and increasingly throws across version bumps. The data-component-safe path (`ItemStack.serializeAsBytes()` / `ItemStack.deserializeBytes()`) is already used by `ShopVaultService` — migrate the two legacy sites to it.

**Hard requirement — backward compatibility:** existing shop rows in the DB are encoded with the OLD format. The new `deserialize` MUST still read them. Strategy: **write new** (`serializeAsBytes`), **read new-then-legacy** (try `deserializeBytes`, fall back to `BukkitObjectInputStream`). Rows migrate to the new format naturally as shops are re-saved. No DB migration, no schema change.

**Out of scope:** the perf batch (M-19/20/21), anything else. This PR touches only `ItemStackSerializer.kt` and `ContainerTradeService.kt` — **disjoint from `ShopRepository`**, so it may run in parallel with the perf batch.

**Architecture:** Hexagonal/SPEAR. Application-layer serializer change; `deserializeStack` delegates to the single serializer (one source of truth for the fallback). TDD.

**Tech Stack:** Kotlin 2.0.0, Paper 1.21.x ItemStack API, JUnit 5 + MockBukkit, detekt 1.23.8.

**Standing rules (every task):**
- Prefix bash with `cd <REPO> &&` (`/d/BadgersMC-Dev/EnthusiaMarket` or `/opt/data/EnthusiaMarket`). On Hermes' box also prefix gradle with `export JAVA_HOME=/opt/data/jdk-21.0.11+10 &&`.
- Every gradle command includes `-Plumaguilds.jar=<JAR> --no-daemon --console=plain`.
- LF→CRLF warnings expected. Branch `fix/serializer-migration` off current `main`. Do not push (coordinator opens the PR).
- TDD. Commit after every task.
- Gate rule: compare each `Run:` to `Expected:`; mismatch → STOP, fix, re-run; HALT after 3 tries. Final gate on the EXACT committed HEAD.

---

## CONFIRMED API SYMBOLS (verified against the repo — use exactly)

- **`ItemStackSerializer`** (`application/ItemStackSerializer.kt`) — a Kotlin `object` (not a bean). Current:
  - `serialize(item: ItemStack): String` → `BukkitObjectOutputStream(baos).use { writeObject(item) }` then Base64.
  - `deserialize(base64: String): ItemStack?` → Base64 decode → `BukkitObjectInputStream(bais).use { readObject() as ItemStack }`, catch → log warning + null.
- **The target API** (already used by `ShopVaultService.kt`): write `Base64.getEncoder().encodeToString(item.serializeAsBytes())`; read `ItemStack.deserializeBytes(Base64.getDecoder().decode(s))`. `serializeAsBytes()` is an instance method; `deserializeBytes(ByteArray): ItemStack` is a static on `ItemStack`. These are **data-component-aware and data-converter-safe**.
- **MockBukkit supports real serialization** — `ShopVaultServiceTest` (`@BeforeTest MockBukkit.mock()`, `ItemStack(Material.DIAMOND)`) round-trips items through `ShopVaultService` which uses `serializeAsBytes`/`deserializeBytes`. So a MockBukkit round-trip test for `ItemStackSerializer` is viable — mirror `ShopVaultServiceTest`'s setup/teardown.
- **`ContainerTradeService.deserializeStack(base64: String): ItemStack?`** (`application/ContainerTradeService.kt`) — `protected open`, currently an inline duplicate of the legacy reader (`BukkitObjectInputStream(stream).readObject() as ItemStack`, catch → null). **`ContainerTradeServiceTest` and `ContainerTradeServiceTradeTest` OVERRIDE this method** (`override fun deserializeStack(...) = mockItemStack`), so changing its body does NOT affect those tests.
- **detekt** — keep the dual-fallback tidy; if it trips `TooGenericExceptionCaught`, `@Suppress` is acceptable here (matches the existing serializer/deserializeStack style which already suppresses/catches broadly).

---

## Task 1: Migrate ItemStackSerializer (write new, read new-then-legacy)

**Files:**
- Modify: `src/main/kotlin/net/badgersmc/em/application/ItemStackSerializer.kt`
- Test (create): `src/test/kotlin/net/badgersmc/em/application/ItemStackSerializerTest.kt`

- [ ] **Step 1: Failing tests** (MockBukkit — mirror `ShopVaultServiceTest` setup/teardown)
```kotlin
class ItemStackSerializerTest {
    @BeforeTest fun setup() = MockBukkit.mock().let {}
    @AfterTest fun teardown() = MockBukkit.unmock()

    @Test fun `new-format round-trip preserves type and amount`() {
        val item = ItemStack(Material.DIAMOND_SWORD, 3)
        val restored = ItemStackSerializer.deserialize(ItemStackSerializer.serialize(item))
        assertEquals(item, restored)
    }

    @Test fun `deserialize reads a legacy BukkitObjectOutputStream blob`() {
        // Build a legacy-format base64 the way the OLD serialize did, then assert the
        // migrated deserialize still reads it (backward compat for existing DB rows).
        val item = ItemStack(Material.IRON_INGOT, 5)
        val legacy = run {
            val baos = java.io.ByteArrayOutputStream()
            org.bukkit.util.io.BukkitObjectOutputStream(baos).use { it.writeObject(item) }
            java.util.Base64.getEncoder().encodeToString(baos.toByteArray())
        }
        assertEquals(item, ItemStackSerializer.deserialize(legacy))
    }

    @Test fun `deserialize returns null on garbage`() {
        assertNull(ItemStackSerializer.deserialize("not-base64-or-item"))
    }
}
```
(If MockBukkit cannot produce a legacy blob in test 2 — i.e. `BukkitObjectOutputStream.writeObject` throws under MockBukkit — HALT and report; do not delete the backward-compat test. It is the whole point of the migration.)

- [ ] **Step 2: Run — verify RED**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew test --tests "*ItemStackSerializerTest" -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: FAIL — test 1 fails because the current legacy `deserialize` can't read a `serializeAsBytes` blob (or the round-trip drops data); test file is new.

- [ ] **Step 3: Implement the migration**

Replace the body of `ItemStackSerializer`:
```kotlin
object ItemStackSerializer {
    private val log = Logger.getLogger(ItemStackSerializer::class.java.name)

    /** Data-component-safe encoding (NBT via serializeAsBytes). */
    fun serialize(item: ItemStack): String =
        Base64.getEncoder().encodeToString(item.serializeAsBytes())

    /**
     * Decode an item. Tries the new NBT format first, then falls back to the
     * legacy BukkitObjectInputStream so rows written before this migration
     * still load. Returns null only if neither format parses.
     */
    fun deserialize(base64: String): ItemStack? {
        val bytes = try {
            Base64.getDecoder().decode(base64)
        } catch (e: IllegalArgumentException) {
            log.warning("ItemStack deserialization: bad base64: ${e.message}")
            return null
        }
        // New format (preferred).
        runCatching { ItemStack.deserializeBytes(bytes) }.getOrNull()?.let { return it }
        // Legacy fallback for pre-migration rows.
        runCatching {
            ByteArrayInputStream(bytes).let { bais ->
                BukkitObjectInputStream(bais).use { it.readObject() as ItemStack }
            }
        }.getOrNull()?.let { return it }
        log.warning("ItemStack deserialization failed: neither NBT nor legacy format parsed.")
        return null
    }
}
```
Keep the legacy imports (`BukkitObjectInputStream`, `ByteArrayInputStream`); drop `BukkitObjectOutputStream` and `ByteArrayOutputStream` (no longer written).

- [ ] **Step 4: Run — verify GREEN**

Run: same as Step 2. Expected: PASS (all 3).

- [ ] **Step 5: Commit**
```bash
cd /d/BadgersMC-Dev/EnthusiaMarket && git add src/main/kotlin/net/badgersmc/em/application/ItemStackSerializer.kt src/test/kotlin/net/badgersmc/em/application/ItemStackSerializerTest.kt
git commit -m "fix(serialize): ItemStackSerializer uses NBT serializeAsBytes, legacy read fallback (M-8)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 2: Route ContainerTradeService.deserializeStack through the serializer

**Files:**
- Modify: `src/main/kotlin/net/badgersmc/em/application/ContainerTradeService.kt`

Not new-test work — the override-based tests already cover behavior; this removes the duplicate legacy reader so container shops get the same NBT + fallback path.

- [ ] **Step 1: Delegate**

Replace the body of `deserializeStack`:
```kotlin
    protected open fun deserializeStack(base64: String): ItemStack? =
        ItemStackSerializer.deserialize(base64)
```
Remove the now-unused inline `BukkitObjectInputStream` / `ByteArrayInputStream` / `Base64` imports **only if** nothing else in the file uses them (check first).

- [ ] **Step 2: Run — verify nothing regressed**

Run: `cd /d/BadgersMC-Dev/EnthusiaMarket && ./gradlew test --tests "*ContainerTradeServiceTest" --tests "*ContainerTradeServiceTradeTest" -Plumaguilds.jar=/d/BadgersMC-Dev/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar --no-daemon --console=plain`
Expected: PASS — both suites override `deserializeStack`, so behavior is unchanged.

- [ ] **Step 3: Commit**
```bash
cd /d/BadgersMC-Dev/EnthusiaMarket && git add src/main/kotlin/net/badgersmc/em/application/ContainerTradeService.kt
git commit -m "refactor(trade): deserializeStack delegates to ItemStackSerializer (M-8)

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
1. **Backward compatibility is the requirement, not a nice-to-have.** The legacy read fallback MUST stay; the `deserialize reads a legacy blob` test is the proof. If it can't be written under MockBukkit, HALT — don't ship without it.
2. **Write new, read both.** `serialize` only emits NBT; `deserialize` tries NBT then legacy. Old rows migrate when their shop is next saved.
3. **`deserializeStack` is overridden in tests** — its body change is invisible to `ContainerTradeServiceTest`/`TradeTest`. That's expected; don't "fix" the tests.
4. **No schema/DB migration** — same Base64 string column; only the bytes' format changes, and the reader handles both.
5. **Don't touch `ShopVaultService`** — it's already on the new API. Scope is the two legacy sites only.
