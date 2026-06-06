# Hermes Execution Handoff — Guild Rent Collection (audit M11)

**Executor model:** DeepSeek V4 Flash (OpenRouter)
**Mode:** Subagent-driven, one task at a time, gated.
**Branch:** `feat/guild-rent`
**Plan:** `docs/superpowers/plans/2026-06-04-guild-rent.md`
**Spec:** `docs/superpowers/specs/2026-06-04-guild-rent-collection-design.md`

Paste everything below the line into Hermes as the controlling prompt.

---

## ROLE

You are the **orchestrator** for a small EnthusiaMarket (EM) feature: collecting rent on GUILD-owned
stalls from the guild bank (audit finding M11 — guild stalls were rent-free forever). 3 tasks. Execute
with **`superpowers:subagent-driven-development`**: hold the plan and gates, dispatch a fresh subagent
per task, verify every gate. Every decision is made — no improvising.

## STEP 0 — Check out the branch

```bash
cd /opt/data/EnthusiaMarket
git fetch origin
git checkout feat/guild-rent
git pull
git status   # clean tree, spec + plan + handoff present
```

## STEP 1 — Read these three files in full before dispatching

- `docs/superpowers/plans/HERMES-HANDOFF-guild-rent.md` (this file)
- `docs/superpowers/plans/2026-06-04-guild-rent.md` — the 3-task plan; **CONFIRMED API SYMBOLS** is authoritative.
- `docs/superpowers/specs/2026-06-04-guild-rent-collection-design.md` — the spec.

3 tasks: (1) `RentCollectionService` guild-bank charge branch, (2) `StallRentExtensionService` guild-bank charge + refund branch, (3) final gate + mark M11.

## ENVIRONMENT (your box)

- Prefix every gradle command with `export JAVA_HOME=/opt/data/jdk-21.0.11+10 &&`.
- Repo `/opt/data/EnthusiaMarket`; lumaguilds jar `/opt/data/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar`. The plan writes `/d/BadgersMC-Dev/...` — substitute `/opt/data/...` everywhere.
- Prefix every bash command with `cd /opt/data/EnthusiaMarket &&` (cwd resets). LF→CRLF warnings expected.

## NON-NEGOTIABLE RULES

1. **One task = one subagent dispatch.** Pass the task's steps, `Files:`, and the relevant CONFIRMED API SYMBOLS verbatim.
2. **You own the gates.** After a subagent reports, YOU run the `Run:` command and compare to `Expected:`. Match → commit (plan's verbatim message + `Co-Authored-By:` trailer) → next. Mismatch → send the subagent back; HALT after 3 tries.
3. **TDD on Tasks 1 & 2.** The subagent writes the test; YOU confirm RED before GREEN. A red test that unexpectedly passes → HALT.
4. **Commit after every task. Do NOT push** — coordinator opens the PR. If a push is later authorized, push to your fork `Hermes-Enthusia/EnthusiaMarket`, never BadgersMC.
5. **Final gate (Task 3) on the EXACT committed HEAD** — paste real output (`clean detekt test shadowJar` → BUILD SUCCESSFUL, detekt 0, tests pass).
6. **Do NOT touch the grace/eviction branches** — only the charge source changes. Feed the branched boolean into the existing success/failure logic. SOLO behaviour must stay identical (corrupt id → Skipped; rentDue ≤ 0 → collected) — the existing SOLO tests are the guard; if one breaks unexpectedly, HALT and re-read.
7. **`owner.id` is the guild id** for GUILD stalls — pass it straight to `bankWithdraw`/`bankDeposit`.
8. **Constructor churn** — `RentCollectionService` gains `guildProvider`; add `mockk<GuildProvider>(relaxed = true)` to any direct-construction test. `StallRentExtensionService` already injects it.
9. **Scope discipline.** Touch ONLY each task's `Files:`. No destructive git.

## HALT CONDITIONS

Verify mismatch after 3 fixes · a red test unexpectedly passes · an existing SOLO rent/extension test breaks in a way that isn't a simple ctor-arg fix (means the charge branch changed SOLO behaviour — re-read) · a symbol can't be resolved by reading the named file · detekt flags `processStall` beyond its existing `@Suppress` (extract a `chargeRent` helper instead of widening suppressions). On HALT: report task + step, exact command, actual vs expected, single best hypothesis.

## DEFINITION OF DONE

- All 3 tasks committed. Final gate → BUILD SUCCESSFUL, detekt 0, tests pass.
- Report the final gate output + commit list. Do NOT push.

## FIRST ACTION

Read the three files, announce your task breakdown, then dispatch the Task 1 subagent.
