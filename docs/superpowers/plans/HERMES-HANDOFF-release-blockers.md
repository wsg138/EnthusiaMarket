# Hermes Execution Handoff — Release-Blocker Batch

**Executor:** DeepSeek V4 Flash. **Mode:** subagent-driven, gated, one task at a time.
**Branch:** `fix/release-blockers` (off `main`, **after PR #40 is merged**).
**Plan:** `docs/superpowers/plans/2026-06-06-release-blockers.md`

Paste everything below the line into Hermes.

---

## ROLE

You are the **orchestrator** for a tiny, high-stakes EnthusiaMarket fix batch: the only two true launch-blockers from hardening audit v2. 3 tasks. Execute with **`superpowers:subagent-driven-development`** — hold the plan + gates, dispatch a fresh subagent per task, verify every gate.

- **C-15:** players can't bid — `/em bid` is OP-gated though the player perm node already exists. One-line annotation fix.
- **C-10:** rent ticker ignores `nextRentAt` → pre-paid extensions are double-charged. One-line guard + TDD.

## STEP 0 — Pre-flight

```bash
cd /opt/data/EnthusiaMarket
git fetch origin
git log --oneline origin/main | grep -i "guild.*rent\|M11" | head   # confirm PR #40 is in main
git checkout -b fix/release-blockers origin/main
```
If PR #40 (guild rent) is NOT in `main`, **STOP and report** — Task 2 depends on it.

## STEP 1 — Read in full before dispatching
- `docs/superpowers/plans/HERMES-HANDOFF-release-blockers.md` (this file)
- `docs/superpowers/plans/2026-06-06-release-blockers.md` — **CONFIRMED API SYMBOLS is authoritative.**

3 tasks: (1) C-15 perm annotation [not TDD], (2) C-10 ticker guard [TDD], (3) final gate.

## ENVIRONMENT (your box)
- Prefix gradle with `export JAVA_HOME=/opt/data/jdk-21.0.11+10 &&`.
- Repo `/opt/data/EnthusiaMarket`; jar `/opt/data/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar`. The plan writes `/d/BadgersMC-Dev/...` — substitute `/opt/data/...`.
- Prefix every bash with `cd /opt/data/EnthusiaMarket &&` (cwd resets). LF→CRLF warnings expected.

## NON-NEGOTIABLE RULES
1. **One task = one subagent dispatch.** Pass the task's steps + Files + relevant CONFIRMED SYMBOLS verbatim.
2. **You own the gates.** Run each `Run:`, compare to `Expected:`. Match → commit (plan's verbatim message + `Co-Authored-By:` trailer) → next. Mismatch → send the subagent back; HALT after 3 tries.
3. **TDD on Task 2 only.** Confirm the "skips … future" test is RED before adding the guard. A red test that unexpectedly passes → HALT.
4. **Task 1 is metadata only** — change the single `@Permission` string on `bid`. Do NOT touch the bid body, do NOT add a perm node (it already exists at build.gradle.kts:197), do NOT wire GUI bidding.
5. **Scope discipline — SMALL PR.** Touch ONLY each task's Files. No guild-correctness, no compensation hardening, no perf, no wiki. No destructive git.
6. **Commit after every task. Do NOT push** — coordinator opens the PR. If push is later authorized, push to your fork `Hermes-Enthusia/EnthusiaMarket`, never BadgersMC.
7. **Final gate (Task 3) on the EXACT committed HEAD** — paste real `clean detekt test shadowJar` output (BUILD SUCCESSFUL, detekt 0, tests pass).

## HALT CONDITIONS
PR #40 not in main · verify mismatch after 3 fixes · the C-10 red test unexpectedly passes · an existing rent test breaks beyond a trivial fix (means the guard skipped GRACE or the null case — re-read Self-Review note 2/3) · a symbol can't be resolved by reading the named file. On HALT: report task + step, exact command, actual vs expected, one best hypothesis.

## DEFINITION OF DONE
- 2 fix commits on `fix/release-blockers`. Final gate → BUILD SUCCESSFUL, detekt 0, tests pass.
- Report the gate output + both commit hashes. Do NOT push.

## FIRST ACTION
Run STEP 0, confirm #40 is in main, read the two files, announce the task breakdown, dispatch Task 1.
