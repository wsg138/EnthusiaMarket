# Hermes Execution Handoff — ItemStack Serializer Migration (M-8)

**Executor:** DeepSeek V4 Flash. **Mode:** subagent-driven, gated, one task at a time.
**Branch:** `fix/serializer-migration` (off current `main`).
**Plan:** `docs/superpowers/plans/2026-06-07-serializer-migration.md`

Paste everything below the line into Hermes.

---

## ROLE

You are the **orchestrator** for an EnthusiaMarket data-integrity fix: shop items are persisted with the legacy `BukkitObjectOutputStream`, which silently drops item data-components on Paper 1.21.x. Migrate to the NBT-safe `serializeAsBytes`/`deserializeBytes` (already used by `ShopVaultService`), keeping a read-fallback so existing DB rows still load. 3 tasks. Use **`superpowers:subagent-driven-development`** — hold the plan + gates, dispatch a fresh subagent per task, verify every gate.

## STEP 0 — Pre-flight
```bash
cd /opt/data/EnthusiaMarket
git fetch origin
git checkout -b fix/serializer-migration origin/main
```

## STEP 1 — Read in full before dispatching
- `docs/superpowers/plans/HERMES-HANDOFF-serializer-migration.md` (this file)
- `docs/superpowers/plans/2026-06-07-serializer-migration.md` — **CONFIRMED API SYMBOLS is authoritative.**

3 tasks: (1) migrate `ItemStackSerializer` (write NBT, read NBT-then-legacy) [TDD], (2) `ContainerTradeService.deserializeStack` delegates to it [verify], (3) final gate.

## ENVIRONMENT (your box)
- Prefix gradle with `export JAVA_HOME=/opt/data/jdk-21.0.11+10 &&`.
- Repo `/opt/data/EnthusiaMarket`; jar `/opt/data/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar`. The plan writes `/d/BadgersMC-Dev/...` — substitute `/opt/data/...`.
- Prefix every bash with `cd /opt/data/EnthusiaMarket &&` (cwd resets). LF→CRLF warnings expected.

## NON-NEGOTIABLE RULES
1. **One task = one subagent dispatch.** Pass the task's steps + Files + relevant CONFIRMED SYMBOLS verbatim.
2. **You own the gates.** Run each `Run:`, compare to `Expected:`. Match → commit (plan's verbatim message + `Co-Authored-By:` trailer) → next. Mismatch → send the subagent back; HALT after 3 tries.
3. **TDD on Task 1.** Confirm RED before GREEN.
4. **Backward compatibility is mandatory** — `serialize` writes NBT only; `deserialize` tries NBT THEN legacy `BukkitObjectInputStream`. The "deserialize reads a legacy blob" test is the proof and MUST pass. If it can't be authored under MockBukkit, HALT — do not ship without it.
5. **Don't fix the ContainerTrade tests** — they override `deserializeStack`, so Task 2's body change is invisible to them. That's expected.
6. **Scope — only `ItemStackSerializer.kt` + `ContainerTradeService.kt` (+ the new test).** Do NOT touch `ShopVaultService` (already migrated), `ShopRepository`, or any schema. No destructive git.
7. **Commit after every task. Do NOT push** — coordinator opens the PR. If push is later authorized, push to your fork `Hermes-Enthusia/EnthusiaMarket`, never BadgersMC.
8. **Final gate (Task 3) on the EXACT committed HEAD** — paste real `clean detekt test shadowJar` output (BUILD SUCCESSFUL, detekt 0, tests pass).

## HALT CONDITIONS
The backward-compat (legacy-blob) test can't be authored/passed under MockBukkit · a red test unexpectedly passes · an existing serialization/trade test breaks · a symbol can't be resolved by reading the named file · detekt flags a touched method beyond an acceptable `@Suppress`. On HALT: report task + step, exact command, actual vs expected, one best hypothesis.

## DEFINITION OF DONE
- 2 commits on `fix/serializer-migration`. Final gate → BUILD SUCCESSFUL, detekt 0, tests pass.
- Report the gate output + both commit hashes. Do NOT push.

## FIRST ACTION
Run STEP 0, read the two files, announce the task breakdown, dispatch Task 1.
