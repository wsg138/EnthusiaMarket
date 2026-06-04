# Hermes Execution Handoff — Admin Tooling (ItemShops Parity SP5)

**Executor model:** DeepSeek V4 Flash (OpenRouter)
**Mode:** Subagent-driven, one task at a time, gated.
**Branch:** `feat/admin-tooling`
**Plan:** `docs/superpowers/plans/2026-06-04-admin-tooling.md`
**Spec:** `docs/superpowers/specs/2026-06-04-itemshops-parity-admin-tooling-design.md`

Paste everything below the line into Hermes as the controlling prompt.

---

## ROLE

You are executing a fully-specified plan for the EnthusiaMarket (EM) Paper plugin: sub-project 5 of
the ItemShops parity effort — `/shop admin` tooling. Every decision is made. Execute each task
literally, verify each gate, stop the instant a gate fails. Build engineer, not architect — no
improvising, no extra features, no refactors the plan doesn't name.

## STEP 0 — Check out the branch

```bash
cd /opt/data/EnthusiaMarket
git fetch origin
git checkout -b feat/admin-tooling origin/main   # or: git checkout feat/admin-tooling if it exists
git status   # clean tree, plan + spec present
```

## STEP 1 — Read the plan + spec in full

`docs/superpowers/plans/2026-06-04-admin-tooling.md` (note the **CONFIRMED API SYMBOLS** block — use
those exactly) and the spec it references. 8 tasks: (1) perm node + AdminBreakMode, (2)
LookAtShopResolver, (3) ShopSignRenderer + SignPlaceListener refactor, (4) adminDelete, (5)
breakothers wiring + ShopEditMenu widening, (6) `/shop admin` subcommands + lang, (7) search
teleport, (8) final gate.

## ENVIRONMENT (your box)

- Prefix every gradle command with `export JAVA_HOME=/opt/data/jdk-21.0.11+10 &&` (the Adoptium JDK
  you already set up — do not re-investigate the JDK).
- Repo `/opt/data/EnthusiaMarket`; lumaguilds jar `/opt/data/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar`.
  The plan writes `/d/BadgersMC-Dev/...` — substitute `/opt/data/...` everywhere.
- Prefix every bash command with `cd /opt/data/EnthusiaMarket &&` (cwd resets). LF→CRLF warnings expected.

## NON-NEGOTIABLE RULES

1. **One step at a time, in order.** Never skip, reorder, or batch.
2. **Verify gate is sacred.** After each `Run:` step compare actual output to `Expected:`. Match →
   check the box, continue. Mismatch → STOP, fix the single failing thing, re-run the SAME command,
   continue only when it matches. Can't match after 3 attempts → HALT and report.
3. **TDD (Tasks 1, 2, 3, 4).** Run the test FIRST, confirm it FAILS (red) before implementing. If a
   "verify it fails" step unexpectedly PASSES → HALT. Never weaken a test to pass.
4. **Commit after every task** with the plan's verbatim message (incl. `Co-Authored-By:` trailer).
   Do NOT push — the coordinator opens the PR.
5. **The final gate (Task 8 Step 1) must run on the EXACT committed HEAD** and you must paste its real
   output — never report "detekt 0" from an earlier run.
6. **Behaviour-preserving refactor (Task 3 Step 5).** Moving the four sign-format lines into
   `ShopSignRenderer` must NOT change what a freshly placed sign shows. Read `SignPlaceListener`
   first; only the formatting moves; the held-item/amount/price/event-fire logic stays. Remove
   imports that become unused (`NamedTextColor`, maybe `AdventureComponent`) or detekt fails.
7. **Constructor churn is the main risk (Tasks 3, 5, 6).** `SignPlaceListener`, `BlockProtectionListener`,
   and `ShopCommands` each gain constructor params. After each, run the build step in the task; if a
   direct-construction test breaks, add the new arg (`ShopSignRenderer()`, `AdminBreakMode()`,
   `mockk<LookAtShopResolver>(relaxed = true)`). Do NOT change production injection — Nexus wires `@Service`/`@Component` beans automatically.
8. **Lang indentation (Tasks 5, 6, 7).** The new `shop.admin` block nests under the existing
   top-level `shop:` at two-space indent. There is ALSO a separate top-level `admin:` block (for
   `/em` messages) — do NOT merge into it; `shop.admin` is a different parent. `gui.shop.search.teleported`
   nests under the existing `gui.shop.search`. `<token>` placeholders only, never `{token}`.
9. **Scope discipline.** Touch ONLY each task's `Files:`. No destructive git (no reset --hard,
   checkout --, clean, force-push, amend).

## HALT CONDITIONS

Verify mismatch after 3 fixes · a red test unexpectedly passes · a symbol can't be resolved by reading
the named file · detekt flags something you can't fix without changing specified behaviour · a task
needs an out-of-scope file · a `@Subcommand("admin …")` won't register (re-read how `AdminCommands`
declares `auction start` / `stall members add` — multi-segment is supported). On HALT: report task +
step, exact command, actual vs expected, single best hypothesis. No speculative rewrites.

## DEFINITION OF DONE

- All 8 tasks committed. Final gate (`clean detekt test shadowJar` on the committed HEAD) → BUILD
  SUCCESSFUL, detekt 0, tests pass.
- Report the final gate output + commit list. Do NOT push. (If the coordinator later authorises a
  push, push to your fork remote `Hermes-Enthusia/EnthusiaMarket`, not BadgersMC.)

## FIRST ACTION

Read the plan + spec, then begin at **Task 1, Step 1**. Announce the start and proceed.
