# Hermes Execution Handoff — Compensation Hardening (C-4, C-11, C-13, C-14)

**Executor:** DeepSeek V4 Flash. **Mode:** subagent-driven, gated, one task at a time.
**Branch:** `fix/compensation-hardening` (off current `main`).
**Plan:** `docs/superpowers/plans/2026-06-07-compensation-hardening.md`

> **This is ONE cohesive PR.** Three of the four findings are in `ShopTradeService.kt` and all four share the new event + alert service. Do NOT split across branches.

Paste everything below the line into Hermes.

---

## ROLE

You are the **orchestrator** for an EnthusiaMarket money-integrity hardening batch: when a trade's rollback/payout *itself* fails, today it only logs a warning — money/items strand with no operator alert. Add a single alert primitive and call it at every terminal compensation-failure site. 4 tasks. Use **`superpowers:subagent-driven-development`** — hold the plan + gates, dispatch a fresh subagent per task, verify every gate.

## STEP 0 — Pre-flight
```bash
cd /opt/data/EnthusiaMarket
git fetch origin
git checkout -b fix/compensation-hardening origin/main
```

## STEP 1 — Read in full before dispatching
- `docs/superpowers/plans/HERMES-HANDOFF-compensation-hardening.md` (this file)
- `docs/superpowers/plans/2026-06-07-compensation-hardening.md` — **CONFIRMED API SYMBOLS is authoritative.**

4 tasks: (1) `TradeCompensationFailedEvent` + `CompensationAlertService` [TDD], (2) `ShopTradeService` alert at 4 sites [TDD], (3) `SellOfferService` alert at the proceeds site [TDD], (4) final gate.

## ENVIRONMENT (your box)
- Prefix gradle with `export JAVA_HOME=/opt/data/jdk-21.0.11+10 &&`.
- Repo `/opt/data/EnthusiaMarket`; jar `/opt/data/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar`. The plan writes `/d/BadgersMC-Dev/...` — substitute `/opt/data/...`.
- Prefix every bash with `cd /opt/data/EnthusiaMarket &&` (cwd resets). LF→CRLF warnings expected.

## NON-NEGOTIABLE RULES
1. **One task = one subagent dispatch.** Pass the task's steps + Files + relevant CONFIRMED SYMBOLS verbatim.
2. **You own the gates.** Run each `Run:`, compare to `Expected:`. Match → commit (plan's verbatim message + `Co-Authored-By:` trailer) → next. Mismatch → send the subagent back; HALT after 3 tries.
3. **TDD on Tasks 1–3.** Confirm RED before GREEN. A red test that unexpectedly passes → HALT.
4. **No control-flow change** — every failure site still returns its existing `CompensationFailed`/result; you ADD an `alerter.alert(...)` call. Existing trade/offer tests MUST stay green. **No escrow/freeze/retry** (out of scope).
5. **Services never touch Bukkit directly** — they call the injected `CompensationAlertService`; only that service fires the event. Keeps everything unit-testable.
6. **Scope — single concern.** Touch only each task's Files. No other audit findings, no domain/schema change, no destructive git.
7. **Commit after every task. Do NOT push** — coordinator opens the PR. If push is later authorized, push to your fork `Hermes-Enthusia/EnthusiaMarket`, never BadgersMC.
8. **Final gate (Task 4) on the EXACT committed HEAD** — paste real `clean detekt test shadowJar` output (BUILD SUCCESSFUL, detekt 0, tests pass).

## HALT CONDITIONS
A red test unexpectedly passes · an existing trade/offer test breaks beyond adding the new `mockk(relaxed = true)` ctor arg (means control flow changed — re-read Self-Review note 1) · a symbol can't be resolved by reading the named file · detekt flags a touched method. On HALT: report task + step, exact command, actual vs expected, one best hypothesis.

## DEFINITION OF DONE
- 3 fix commits on `fix/compensation-hardening`. Final gate → BUILD SUCCESSFUL, detekt 0, tests pass.
- Report the gate output + the 3 commit hashes. Do NOT push.

## FIRST ACTION
Run STEP 0, read the two files, announce the task breakdown, dispatch Task 1.
