# Hermes Execution Handoff — Bulk Shop Ops (M-21)

**Executor:** DeepSeek V4 Flash. **Mode:** subagent-driven, gated, one task at a time.
**Branch:** `fix/perf-bulk-ops` (off current `main`).
**Plan:** `docs/superpowers/plans/2026-06-07-perf-m21-bulk-ops.md`

> First of three sequential perf PRs (M-21 → M-20 → M-19). They all touch `ShopRepository`, so they ship one at a time. This is M-21 only.

Paste everything below the line into Hermes.

---

## ROLE

You are the **orchestrator** for a small EnthusiaMarket perf fix: remove the N+1 JDBC round-trips in `ShopManagementService.deleteAll/trustAll/untrustAll`. Add a bulk `deleteByOwner` and stop re-reading shops that were already loaded. 3 tasks. Use **`superpowers:subagent-driven-development`** — hold the plan + gates, dispatch a fresh subagent per task, verify every gate.

## STEP 0 — Pre-flight
```bash
cd /opt/data/EnthusiaMarket
git fetch origin
git checkout -b fix/perf-bulk-ops origin/main
```

## STEP 1 — Read in full before dispatching
- `docs/superpowers/plans/HERMES-HANDOFF-perf-m21.md` (this file)
- `docs/superpowers/plans/2026-06-07-perf-m21-bulk-ops.md` — **CONFIRMED API SYMBOLS is authoritative.**

3 tasks: (1) `ShopRepository.deleteByOwner` + impls [build], (2) `ShopManagementService` bulk delete + skip re-reads [TDD], (3) final gate.

## ENVIRONMENT (your box)
- Prefix gradle with `export JAVA_HOME=/opt/data/jdk-21.0.11+10 &&`.
- Repo `/opt/data/EnthusiaMarket`; jar `/opt/data/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar`. The plan writes `/d/BadgersMC-Dev/...` — substitute `/opt/data/...`.
- Prefix every bash with `cd /opt/data/EnthusiaMarket &&` (cwd resets). LF→CRLF warnings expected.

## NON-NEGOTIABLE RULES
1. **One task = one subagent dispatch.** Pass the task's steps + Files + relevant CONFIRMED SYMBOLS verbatim.
2. **You own the gates.** Run each `Run:`, compare to `Expected:`. Match → commit (plan's verbatim message + `Co-Authored-By:` trailer) → next. Mismatch → send the subagent back; HALT after 3 tries.
3. **TDD on Task 2.** Confirm RED before GREEN.
4. **Add `deleteByOwner` to EVERY `ShopRepository` implementor** (grep `: ShopRepository`) or the build breaks — production `ShopRepositorySql` + any in-memory test fake.
5. **Behavior preserved** — `deleteAll` still fires one `ShopDeletedEvent` per owned shop; `mutateOwned` stays for the menu path (`trust`/`untrust` with explicit ids). Don't delete it.
6. **Scope — M-21 only.** No schema change, no GUI, no search work (that's the next two PRs). Touch only the plan's Files. No destructive git.
7. **Commit after every task. Do NOT push** — coordinator opens the PR. If push is later authorized, push to your fork `Hermes-Enthusia/EnthusiaMarket`, never BadgersMC.
8. **Final gate (Task 3) on the EXACT committed HEAD** — paste real `clean detekt test shadowJar` output (BUILD SUCCESSFUL, detekt 0, tests pass).

## HALT CONDITIONS
A red test unexpectedly passes · the build fails because an implementor is missing `deleteByOwner` (add it — that's expected, not a HALT, unless you can't locate it) · an existing trust/delete menu-path test breaks · a symbol can't be resolved by reading the named file · detekt flags a touched method. On HALT: report task + step, exact command, actual vs expected, one best hypothesis.

## DEFINITION OF DONE
- 2 commits on `fix/perf-bulk-ops`. Final gate → BUILD SUCCESSFUL, detekt 0, tests pass.
- Report the gate output + both commit hashes. Do NOT push.

## FIRST ACTION
Run STEP 0, read the two files, announce the task breakdown, dispatch Task 1.
