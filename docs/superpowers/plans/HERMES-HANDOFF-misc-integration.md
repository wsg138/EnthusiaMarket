# Hermes Execution Handoff — Misc / Integration (ItemShops Parity SP6)

**Executor model:** DeepSeek V4 Flash (OpenRouter)
**Mode:** Subagent-driven, one task at a time, gated.
**Branch:** `feat/misc-integration`
**Plan:** `docs/superpowers/plans/2026-06-04-misc-integration.md`
**Spec:** `docs/superpowers/specs/2026-06-04-itemshops-parity-misc-integration-design.md`

Paste everything below the line into Hermes as the controlling prompt.

---

## ROLE

You are the **orchestrator** for sub-project 6 of the EnthusiaMarket (EM) ItemShops parity effort —
misc/integration (transaction log + `/shop history`, owner sale notifications, PAPI placeholders,
sign-click info card). Execute it with the **`superpowers:subagent-driven-development`** workflow: you
hold the plan and gates and dispatch a fresh subagent per task, verifying every gate yourself. Every
decision is made — no improvising, no extra features, no refactors the plan doesn't name.

## STEP 0 — Check out the branch

```bash
cd /opt/data/EnthusiaMarket
git fetch origin
git checkout feat/misc-integration
git pull
git status   # clean tree, spec + plan + handoff present
```

## STEP 1 — Read these three files in full before dispatching anything

- `docs/superpowers/plans/HERMES-HANDOFF-misc-integration.md` (this file)
- `docs/superpowers/plans/2026-06-04-misc-integration.md` — the 8-task plan. The **CONFIRMED API SYMBOLS** block is authoritative — pass the relevant symbols into each subagent verbatim.
- `docs/superpowers/specs/2026-06-04-itemshops-parity-misc-integration-design.md` — the spec.

8 tasks: (1) ShopTransaction + V015 + repository, (2) enrich PostShopTransactionEvent + recorder, (3) `/shop history`, (4) owner notifications, (5) PAPI expansion + dep + onEnable, (6) sign-click info card, (7) retention prune, (8) final gate.

## ENVIRONMENT (your box)

- Prefix every gradle command with `export JAVA_HOME=/opt/data/jdk-21.0.11+10 &&`.
- Repo `/opt/data/EnthusiaMarket`; lumaguilds jar `/opt/data/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar`. The plan writes `/d/BadgersMC-Dev/...` — substitute `/opt/data/...` everywhere.
- Prefix every bash command with `cd /opt/data/EnthusiaMarket &&` (cwd resets). LF→CRLF warnings expected.

## NON-NEGOTIABLE RULES

1. **One task = one subagent dispatch.** Give the subagent only that task's steps, its `Files:`, and the CONFIRMED API SYMBOLS it needs. Subagents implement; they don't decide scope.
2. **You own the gates.** After a subagent reports, YOU run the task's `Run:` command and compare to `Expected:`. Match → commit (plan's verbatim message + `Co-Authored-By:` trailer) → next task. Mismatch → send the subagent back with the exact failure; HALT after 3 tries.
3. **TDD on Tasks 1, 5, 6.** The subagent writes the test; YOU confirm it FAILS (red) before implementing, then confirm GREEN. A red test that unexpectedly passes → HALT.
4. **Commit after every task. Do NOT push** — the coordinator opens the PR. If a push is later authorized, push to your fork `Hermes-Enthusia/EnthusiaMarket`, never BadgersMC.
5. **Final gate (Task 8) must run on the EXACT committed HEAD** — paste its real output (`clean detekt test shadowJar` → BUILD SUCCESSFUL, detekt 0, tests pass).
6. **Migration is SQLite syntax** (Task 1) — `INTEGER PRIMARY KEY AUTOINCREMENT`, matching `shop_items`. The persistence test (MockBukkit + real migration) is the proof.
7. **Event back-compat** (Task 2) — the two new `PostShopTransactionEvent` params (`shopId`, `direction`) MUST be trailing and defaulted, so existing construction compiles; only `ContainerTradeService` passes real values.
8. **New dependency version must match** (Task 5) — `nexus-papi:v2.2.1`, identical to the other `nexus-*` modules. PAPI is provide-side only; `registerNexusExpansions` no-ops without PlaceholderAPI.
9. **Lang indentation** (Tasks 3, 4, 6) — the new `shop.history`, `shop.notify`, `shop.info` blocks nest under the existing top-level `shop:` at two-space indent. Do NOT use unquoted `on:`/`off:`/`yes:`/`no:` keys (SnakeYAML parses them as booleans). `<token>` placeholders only.
10. **Constructor churn** (Tasks 2–7) — `ContainerTradeService`, `ShopCommands`, `ShopInteractListener` gain deps; update any direct-construction test with the new args (`mockk(relaxed = true)` for repos). Nexus injects them in production.
11. **Scope discipline.** Touch ONLY each task's `Files:`. No destructive git (no reset --hard, checkout --, clean, force-push, amend).

## HALT CONDITIONS

Verify mismatch after 3 fixes · a red test unexpectedly passes · a symbol can't be resolved by reading the named file · detekt flags something you can't fix without changing specified behaviour · a task needs an out-of-scope file · the V015 migration fails to apply in the persistence test (re-check SQLite syntax) · `nexus-papi` won't resolve (confirm the `v2.2.1` coordinate matches the other Nexus deps). On HALT: report task + step, exact command, actual vs expected, single best hypothesis. No speculative rewrites.

## DEFINITION OF DONE

- All 8 tasks committed. Final gate on the committed HEAD → BUILD SUCCESSFUL, detekt 0, tests pass.
- Report the final gate output + commit list. Do NOT push.

## FIRST ACTION

Read the three files, announce your task breakdown, then dispatch the Task 1 subagent.
