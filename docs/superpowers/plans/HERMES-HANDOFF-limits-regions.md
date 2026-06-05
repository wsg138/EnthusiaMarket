# Hermes Execution Handoff — Limits + Market Regions (ItemShops Parity SP4)

**Executor model:** DeepSeek V4 Flash (OpenRouter)
**Mode:** Subagent-driven, one task at a time, gated.
**Branch:** `feat/limits-regions`
**Plan:** `docs/superpowers/plans/2026-06-04-limits-regions.md`
**Spec:** `docs/superpowers/specs/2026-06-04-itemshops-parity-limits-regions-design.md`

Paste everything below the line into Hermes as the controlling prompt.

---

## ROLE

You are the **orchestrator** for sub-project 4 — the **final** sub-project of the EnthusiaMarket (EM)
ItemShops parity effort: per-player stall-ownership limits. The limit engine already exists; this is a
completion job (6 tasks). Execute with **`superpowers:subagent-driven-development`**: hold the plan and
gates yourself, dispatch a fresh subagent per task, verify every gate. Every decision is made — no
improvising, no extra features, no refactors the plan doesn't name.

## STEP 0 — Check out the branch

```bash
cd /opt/data/EnthusiaMarket
git fetch origin
git checkout feat/limits-regions
git pull
git status   # clean tree, spec + plan + handoff present
```

## STEP 1 — Read these three files in full before dispatching anything

- `docs/superpowers/plans/HERMES-HANDOFF-limits-regions.md` (this file)
- `docs/superpowers/plans/2026-06-04-limits-regions.md` — the 6-task plan. The **CONFIRMED API SYMBOLS** block is authoritative — pass the relevant symbols into each subagent verbatim.
- `docs/superpowers/specs/2026-06-04-itemshops-parity-limits-regions-design.md` — the spec.

6 tasks: (1) **fix `effectiveLimits` no-group=unlimited** — do this first, everything depends on it; (2) `StallOwnershipCounter`; (3) wire counter + real `stall.kind` into the auction gate; (4) gate the buyout path; (5) `/em limit` + bypass node + lang; (6) final gate.

## ENVIRONMENT (your box)

- Prefix every gradle command with `export JAVA_HOME=/opt/data/jdk-21.0.11+10 &&`.
- Repo `/opt/data/EnthusiaMarket`; lumaguilds jar `/opt/data/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar`. The plan writes `/d/BadgersMC-Dev/...` — substitute `/opt/data/...` everywhere.
- Prefix every bash command with `cd /opt/data/EnthusiaMarket &&` (cwd resets). LF→CRLF warnings expected.

## NON-NEGOTIABLE RULES

1. **One task = one subagent dispatch.** Give the subagent only that task's steps, its `Files:`, and the CONFIRMED API SYMBOLS it needs. Subagents implement; they don't decide scope.
2. **You own the gates.** After a subagent reports, YOU run the task's `Run:` command and compare to `Expected:`. Match → commit (plan's verbatim message + `Co-Authored-By:` trailer) → next task. Mismatch → send the subagent back with the exact failure; HALT after 3 tries.
3. **TDD on Tasks 1, 2, 4** (and the auction test in Task 3). The subagent writes the test; YOU confirm it FAILS (red) before implementing, then confirm GREEN. A red test that unexpectedly passes → HALT.
4. **Commit after every task. Do NOT push** — the coordinator opens the PR. If a push is later authorized, push to your fork `Hermes-Enthusia/EnthusiaMarket`, never BadgersMC.
5. **Final gate (Task 6) on the EXACT committed HEAD** — paste real output (`clean detekt test shadowJar` → BUILD SUCCESSFUL, detekt 0, tests pass).
6. **Task 1 is the linchpin.** The no-group=unlimited fix MUST land first — without it, gating the buyout path (Task 4) rejects every purchase on a default (empty-`limits`) server. If an existing `LimitResolutionServiceTest` asserted `total = 0` for a no-group player, that test encoded the bug — update it to the unlimited semantics.
7. **Counting is SOLO-only.** `StallOwnershipCounter` filters `owner.type == OwnerType.SOLO && owner.id == player.toString()`. Guild stalls never count. Both the auction (Task 3) and buyout (Task 4) gates go through it.
8. **The buyout gate is conditioned on `owner.type == SOLO`** (Task 4). `buyForOwner` is shared by `buy` (SOLO) and `buyForGuild` (GUILD); the `== SOLO` condition skips guild buys. Don't gate them separately. Place the gate AFTER the NotFound/AuctionLive/AlreadyOwned guards and BEFORE the economy charge.
9. **Limit rejections return `Result.Rejected(plainString)`** — matches the existing `SellOfferService` convention; no new reject lang keys. Only `/em limit` needs lang (`admin.limit.*`).
10. **Constructor churn** (Tasks 3, 4, 5) — `AuctionLifecycleService`, `StallBuyoutService`, `AdminCommands` gain deps; update any direct-construction test with `mockk(relaxed = true)` args. Nexus injects them in production.
11. **Lang indentation** — the new `limit:` block nests under its parent at the right indent; confirm the prefix (`admin.limit.*`) against `en_US.yml`. No unquoted `on:`/`off:`/`yes:`/`no:` keys. `<token>` only.
12. **Scope discipline.** Touch ONLY each task's `Files:`. No destructive git.

## HALT CONDITIONS

Verify mismatch after 3 fixes · a red test unexpectedly passes · a symbol can't be resolved by reading the named file · detekt flags something unfixable without changing specified behaviour · a task needs an out-of-scope file · an existing limit/auction/buyout test breaks in a way that isn't a simple constructor-arg or updated-assertion fix (could signal the no-group fix changed semantics elsewhere — re-read before forcing). On HALT: report task + step, exact command, actual vs expected, single best hypothesis. No speculative rewrites.

## DEFINITION OF DONE

- All 6 tasks committed. Final gate on the committed HEAD → BUILD SUCCESSFUL, detekt 0, tests pass.
- Report the final gate output + commit list. Do NOT push.

## FIRST ACTION

Read the three files, announce your task breakdown, then dispatch the Task 1 subagent.
