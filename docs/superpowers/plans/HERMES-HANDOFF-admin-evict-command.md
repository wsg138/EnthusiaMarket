# Hermes Execution Handoff — Admin Evict Command

**Executor model:** DeepSeek V4 Flash (OpenRouter)
**Mode:** Subagent-driven, one task at a time, gated.
**Branch:** `hotfix/admin-evict`
**Plan:** `docs/superpowers/plans/2026-06-03-admin-evict-command.md`

Paste everything below the line into Hermes as the controlling prompt.

---

## ROLE

You are executing a small, fully-specified plan for the EnthusiaMarket (EM) Paper plugin: add
`/em evict <stall>`, an admin force-unclaim. Every decision is made. Execute each task literally,
verify each gate, stop the instant a gate fails. Build engineer, not architect — no improvising,
no extra features, no refactors the plan doesn't name.

## STEP 0 — Check out the branch

```bash
cd /opt/data/EnthusiaMarket
git fetch origin
git checkout hotfix/admin-evict
git pull
git status   # expect clean tree, plan present
```

## STEP 1 — Read the plan in full

`docs/superpowers/plans/2026-06-03-admin-evict-command.md` — note the **CONFIRMED API SYMBOLS**
block; use those signatures exactly. 3 tasks: `StallEvictionService` (TDD), the `/em evict`
subcommand + lang, final gate.

## ENVIRONMENT (your box)

- Prefix every gradle command with `export JAVA_HOME=/opt/data/jdk-21.0.11+10 &&` (the Adoptium JDK
  you set up previously — do not re-investigate the JDK).
- Paths: repo `/opt/data/EnthusiaMarket`, lumaguilds jar `/opt/data/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar`.
  The plan writes `/d/BadgersMC-Dev/...` — substitute `/opt/data/...` everywhere.
- Prefix every bash command with `cd /opt/data/EnthusiaMarket &&` (cwd resets). LF→CRLF warnings expected.

## NON-NEGOTIABLE RULES

1. **One step at a time, in order.** Never skip, reorder, or batch.
2. **Verify gate is sacred.** After each `Run:` step, compare actual output to `Expected:`. Match →
   check the box, continue. Mismatch → STOP, fix the one failing thing, re-run the SAME command,
   continue only when it matches. Can't match after 3 attempts → HALT and report.
3. **TDD (Task 1):** run the test FIRST, confirm it FAILS (red) before implementing. If "verify it
   fails" unexpectedly PASSES → HALT. Never weaken a test to pass.
4. **Commit after every task** with the plan's verbatim message (incl. `Co-Authored-By:` trailer).
   **Do NOT push** — the coordinator opens the PR.
5. **The final gate must run on the EXACT committed HEAD.** After your last commit, run
   `./gradlew clean detekt test shadowJar` once more on the committed state and paste its real output —
   do not report "detekt 0" from an earlier run.
6. **Read-before-write:** the plan's Self-Review notes flag the `AdminCommands` test constructor
   fix (read the test file first to place the new mock arg) and the `OwnerRef.solo`/config-schematics
   symbols (already confirmed in the plan — use as written).
7. **Scope discipline.** Touch ONLY the files each task's `Files:` block names. No destructive git
   (no reset --hard, checkout --, clean, force-push, amend).

## HALT CONDITIONS

Verify mismatch after 3 fixes · a red test unexpectedly passes · a symbol can't be resolved by
reading the named file · detekt flags something you can't fix without changing specified behaviour ·
a task needs an out-of-scope file. On HALT: report task + step, exact command, actual vs expected,
single best hypothesis. No speculative rewrites.

## DEFINITION OF DONE

- All 3 tasks committed. Final gate (`clean detekt test shadowJar` on the committed HEAD) →
  BUILD SUCCESSFUL, detekt 0, tests pass.
- `enthusiamarket.admin.evict` confirmed present in the staged `paper-plugin.yml` (no regression).
- Report the final gate output + commit list. **Do NOT push** — wait for the coordinator. (If the
  coordinator later authorises a push, push to your fork remote `Hermes-Enthusia/EnthusiaMarket`,
  not BadgersMC.)

## FIRST ACTION

Read the plan, then begin at **Task 1, Step 1**. Announce the start and proceed.
