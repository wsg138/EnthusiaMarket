# Hermes Execution Handoff — Barter / Profits Vault (ItemShops Parity SP3)

**Executor model:** DeepSeek V4 Flash (OpenRouter)
**Mode:** Subagent-driven, one task at a time, gated.
**Branch:** `feat/barter-vault`
**Plan:** `docs/superpowers/plans/2026-06-04-barter-vault.md`
**Spec:** `docs/superpowers/specs/2026-06-04-itemshops-parity-barter-vault-design.md`

Paste everything below the line into Hermes as the controlling prompt.

---

## ROLE

You are the **orchestrator** for sub-project 3 of the EnthusiaMarket (EM) ItemShops parity effort —
barter shops + a per-owner item vault. This is the **largest sub-project (9 tasks)** and the first of
the two architectural forks. Execute it with the **`superpowers:subagent-driven-development`**
workflow: hold the plan and gates yourself, dispatch a fresh subagent per task, verify every gate.
Every decision is made — no improvising, no extra features, no refactors the plan doesn't name.

## STEP 0 — Check out the branch

```bash
cd /opt/data/EnthusiaMarket
git fetch origin
git checkout feat/barter-vault
git pull
git status   # clean tree, spec + plan + handoff present
```

## STEP 1 — Read these three files in full before dispatching anything

- `docs/superpowers/plans/HERMES-HANDOFF-barter-vault.md` (this file)
- `docs/superpowers/plans/2026-06-04-barter-vault.md` — the 9-task plan. The **CONFIRMED API SYMBOLS** block is authoritative — pass the relevant symbols into each subagent verbatim.
- `docs/superpowers/specs/2026-06-04-itemshops-parity-barter-vault-design.md` — the spec.

9 tasks: (1) `SignDirection.TRADE` + exhaustive-when fixups, (2) ShopVault domain+V016+repo, (3) ShopVaultService (Paper NBT), (4) `executeTrade` barter path, (5) PurchaseMenu TRADE routing, (6) `[TRADE]` sign parse + guild rejection + renderer, (7) `/shopvault` + GUI + perm, (8) ShopEditMenu cost-from-hand, (9) final gate.

## ENVIRONMENT (your box)

- Prefix every gradle command with `export JAVA_HOME=/opt/data/jdk-21.0.11+10 &&`.
- Repo `/opt/data/EnthusiaMarket`; lumaguilds jar `/opt/data/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar`. The plan writes `/d/BadgersMC-Dev/...` — substitute `/opt/data/...` everywhere.
- Prefix every bash command with `cd /opt/data/EnthusiaMarket &&` (cwd resets). LF→CRLF warnings expected.

## NON-NEGOTIABLE RULES

1. **One task = one subagent dispatch.** Give the subagent only that task's steps, its `Files:`, and the CONFIRMED API SYMBOLS it needs. Subagents implement; they don't decide scope.
2. **You own the gates.** After a subagent reports, YOU run the task's `Run:` command and compare to `Expected:`. Match → commit (plan's verbatim message + `Co-Authored-By:` trailer) → next task. Mismatch → send the subagent back with the exact failure; HALT after 3 tries.
3. **TDD on Tasks 2, 3, 4** (and Task 6's renderer test). The subagent writes the test; YOU confirm it FAILS (red) before implementing, then confirm GREEN. A red test that unexpectedly passes → HALT.
4. **Commit after every task. Do NOT push** — the coordinator opens the PR. If a push is later authorized, push to your fork `Hermes-Enthusia/EnthusiaMarket`, never BadgersMC.
5. **Final gate (Task 9) on the EXACT committed HEAD** — paste real output (`clean detekt test shadowJar` → BUILD SUCCESSFUL, detekt 0, tests pass).
6. **Paper NBT serialization (Task 3)** — use `itemStack.serializeAsBytes()` (instance) + `ItemStack.deserializeBytes(bytes)` (static) + `java.util.Base64`. These are Paper 1.21.11 APIs; do NOT substitute the legacy `ItemStackSerializer`/`BukkitObjectOutputStream`. Always normalize `amount = 1` before computing the vault key.
7. **Enum-exhaustiveness ripple (Task 1)** — adding `TRADE` breaks `when (direction)` in `PurchaseMenu` and `ShopTradeService`, and the color `if` in `ShopSignRenderer`. Task 1 adds a temporary `executeTrade` stub (returns `Failure`) so it compiles; **Task 4 replaces the stub with the real body.** Don't leave the stub wired into the menu after Task 4.
8. **Renderer signature change (Task 6)** — `ShopSignRenderer.lines(... price: Long)` → `... costDisplay: String`. This touches THREE callers: `SignPlaceListener`, `ShopCommands.reRenderShopSign` (from the merged SP5), and `ShopSignRendererTest`. Update all three or it won't compile.
9. **Guild rejection (Task 6)** — `[TRADE]` on a stall whose `stall.owner.type == OwnerType.GUILD` must be rejected at sign creation. TRADE shops are SOLO-only by construction.
10. **No-arg command shape (Task 7)** — before assuming `@Subcommand("")` opens `/shopvault`, read how an existing EM command declares its default/base handler and match it; if Nexus needs a subcommand, expose `/shopvault open` instead.
11. **Lang indentation** — new blocks (`gui.shop.trade_*`, `shop.create.no_guild_trade`/`invalid_trade_cost`, `gui.vault.*`, `gui.shop.edit.cost_*`) nest under their existing parents at the right indent. No unquoted `on:`/`off:`/`yes:`/`no:` keys (SnakeYAML booleans). `<token>` placeholders only.
12. **Scope discipline.** Touch ONLY each task's `Files:`. No destructive git.

## HALT CONDITIONS

Verify mismatch after 3 fixes · red test unexpectedly passes · a symbol can't be resolved by reading the named file · detekt flags something unfixable without changing specified behaviour · a task needs an out-of-scope file · `serializeAsBytes`/`deserializeBytes` don't resolve (you're on the wrong Paper API version — STOP and report) · V016 fails to apply in the persistence test (re-check SQLite `ON CONFLICT` syntax) · the no-arg `/shopvault` won't register (re-read an existing command's default handler). On HALT: report task + step, exact command, actual vs expected, single best hypothesis. No speculative rewrites.

## DEFINITION OF DONE

- All 9 tasks committed. Final gate on the committed HEAD → BUILD SUCCESSFUL, detekt 0, tests pass.
- Report the final gate output + commit list. Do NOT push.

## FIRST ACTION

Read the three files, announce your task breakdown, then dispatch the Task 1 subagent.
