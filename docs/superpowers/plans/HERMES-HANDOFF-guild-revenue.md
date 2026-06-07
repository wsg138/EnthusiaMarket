# Hermes Execution Handoff — Guild Revenue Routing (M-15 + M-18)

**Executor:** DeepSeek V4 Flash. **Mode:** subagent-driven, gated, one task at a time.
**Branch:** `fix/guild-revenue` (off current `main`).
**Plan:** `docs/superpowers/plans/2026-06-07-guild-revenue.md`

Paste everything below the line into Hermes.

---

## ROLE

You are the **orchestrator** for a small EnthusiaMarket fix batch: stop guild-owned value leaking to individual members. 3 tasks. Use **`superpowers:subagent-driven-development`** — hold the plan + gates, dispatch a fresh subagent per task, verify every gate.

- **M-18:** sell-offer proceeds on a GUILD-owned stall must pay the **guild bank**, not the selling member.
- **M-15:** a BUY/SELL sign shop placed in a GUILD stall must **bind to the guild** (set `Shop.guildId`) so container-trade revenue routes to the guild bank. The routing already exists — you only set the field.

## STEP 0 — Pre-flight
```bash
cd /opt/data/EnthusiaMarket
git fetch origin
git checkout -b fix/guild-revenue origin/main
```

## STEP 1 — Read in full before dispatching
- `docs/superpowers/plans/HERMES-HANDOFF-guild-revenue.md` (this file)
- `docs/superpowers/plans/2026-06-07-guild-revenue.md` — **CONFIRMED API SYMBOLS is authoritative.**

3 tasks: (1) M-18 SellOfferService proceeds [TDD], (2) M-15 SignPlaceListener guildId [TDD], (3) final gate.

## ENVIRONMENT (your box)
- Prefix gradle with `export JAVA_HOME=/opt/data/jdk-21.0.11+10 &&`.
- Repo `/opt/data/EnthusiaMarket`; jar `/opt/data/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar`. The plan writes `/d/BadgersMC-Dev/...` — substitute `/opt/data/...`.
- Prefix every bash with `cd /opt/data/EnthusiaMarket &&` (cwd resets). LF→CRLF warnings expected.

## NON-NEGOTIABLE RULES
1. **One task = one subagent dispatch.** Pass the task's steps + Files + relevant CONFIRMED SYMBOLS verbatim.
2. **You own the gates.** Run each `Run:`, compare to `Expected:`. Match → commit (plan's verbatim message + `Co-Authored-By:` trailer) → next. Mismatch → send the subagent back; HALT after 3 tries.
3. **TDD on Tasks 1 & 2.** Confirm RED before GREEN. A red test that unexpectedly passes → HALT.
4. **M-15 sets a field only** — do NOT add deposit/routing logic to the listener (`ContainerTradeService` already routes on `shop.guildId`). **M-18 keeps the buyer SOLO** — only the proceeds destination changes.
5. **Scope — SMALL PR.** Touch ONLY each task's Files. No disband cleanup (M-16), no domain/schema change, no legacy `ShopTradeService`/`ShopSign` path. No destructive git.
6. **Commit after every task. Do NOT push** — coordinator opens the PR. If push is later authorized, push to your fork `Hermes-Enthusia/EnthusiaMarket`, never BadgersMC.
7. **Final gate (Task 3) on the EXACT committed HEAD** — paste real `clean detekt test shadowJar` output (BUILD SUCCESSFUL, detekt 0, tests pass).

## HALT CONDITIONS
A red test unexpectedly passes · an existing SOLO sell-offer or sign-placement test breaks beyond a trivial fix (means the owner-type branch changed SOLO behaviour — re-read Self-Review notes 2/4) · a symbol can't be resolved by reading the named file · detekt flags a touched method. On HALT: report task + step, exact command, actual vs expected, one best hypothesis.

## DEFINITION OF DONE
- 2 fix commits on `fix/guild-revenue`. Final gate → BUILD SUCCESSFUL, detekt 0, tests pass.
- Report the gate output + both commit hashes. Do NOT push.

## FIRST ACTION
Run STEP 0, read the two files, announce the task breakdown, dispatch Task 1.
