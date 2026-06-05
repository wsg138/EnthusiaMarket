# ItemShops Parity — Sub-project 4: Limits + Market Regions

**Date:** 2026-06-04
**Status:** Approved (brainstorming complete)
**Owner:** BadgersMC
**Plugin:** EnthusiaMarket (EM)

## 0. Context — the ItemShops parity programme

EM deprecates **ItemShops** (p2wn). Six sub-projects, build order **1 → 2 → 5 → 6 → 3 → 4**:

1. Shop management — done (PR #26)
2. Shop search — done (PR #28)
3. Barter / profits vault — done (PR #34)
4. **Limits + market regions** ← *this spec* (the last sub-project)
5. Admin tooling — done (PR #31)
6. Misc/integration — done (PR #33)

This sub-project **completes** the per-player stall-ownership limit system (REQ-210/211/212/202). The
engine already exists — SP4 closes the deferred gaps rather than building anew.

## 1. Scope of this sub-project

What already exists (do not rebuild):
- **`LimitResolutionService`** — `effectiveLimits(player)` (intersect groups the player holds by
  `enthusiamarket.limit.<group>`, best-per-dimension) and `canClaim(player, kind, currentTotal,
  currentForKind): ClaimDecision` (`Allowed` / `Rejected.TotalCapReached` / `Rejected.KindCapReached`),
  with admin bypass via `BYPASS_NODE = "enthusiamarket.admin.bypasslimit"`.
- The config block `limits.<group>` (`total` + `regionkinds: Map<String,Int>`, `-1` = unlimited).
- `Stall.kind` (region purpose: `default`/`shop`/`farm`, from REQ-220).
- Limit enforcement on the **auction** acquisition path (`AuctionLifecycleService`).

What SP4 adds:

| Gap | Fix |
|---|---|
| **No-group = total 0 = reject-all** (latent bug) | `effectiveLimits` must treat a player in **no applicable group** as *unlimited*, not capped at 0. See below — this protects every gate including the existing auction path. |
| Buyout / sign-purchase path is **ungated** | Apply `canClaim` in `StallBuyoutService.buyForOwner` before charging. |
| Auction gate uses a `"default"` kind placeholder | Pass the stall's real `kind` + a kind-restricted count, so `regionkinds` caps function. |
| No player visibility | `/em limit` shows effective caps + current usage. |
| `bypasslimit` node not in the DSL | Add `enthusiamarket.admin.bypasslimit` to the nexus-permissions DSL. |

### Critical fix — "no applicable group = unlimited"

`effectiveLimits` currently starts `total = 0` and merges in each *granted* group's caps. A player
who holds **no** `enthusiamarket.limit.<group>` node (including the default state where `config.limits`
is empty) ends with `total = 0`, and `canClaim` then rejects every claim (`currentTotal >= 0`). On a
fresh server with no limit groups configured this blocks **all** stall acquisition — auctions
included. SP4 fixes the semantics: **if no group applies to the player, they are unlimited.** Limits
only bind a player who has been *explicitly* placed in a configured group. The admin-bypass path
already returns unlimited; this extends the same default to the un-grouped majority. A regression
test pins it.

### The personal-ownership rule (confirmed)

Personal limits count **only SOLO-owned stalls** — `owner.type == OwnerType.SOLO && owner.id ==
player`. Guild-owned stalls (`owner.type == GUILD`) never count toward an individual's cap, so:
- a guild owner can still hold personal stalls while their guild owns stalls;
- converting a stall to guild ownership drops it from the personal count automatically (the SOLO filter).

This is already how the auction path counts; SP4 makes it the single shared rule. `kind`
(`default`/`shop`/`farm`) is an independent region-purpose dimension used only for the per-kind caps.

### Out of scope
- Guild-level stall caps (a separate guild limit) — future work.
- Limits on shop **signs** within a stall (limits are per-stall-ownership, per the spec).
- Retroactive enforcement on owners already over a (newly tightened) limit.
- Gating `buyForGuild` — a guild claim is not a personal claim, so it is **not** personal-limit-gated.

## 2. Architecture

Hexagonal/SPEAR. One small shared counter, gate insertions at the acquisition services, a read-only
command. `LimitResolutionService` stays Bukkit-free (it already takes counts as parameters).

### New unit

- **`StallOwnershipCounter`** (`application/`) — `@Service`. `counts(player: UUID): OwnedCounts`
  where `OwnedCounts(total: Int, byKind: Map<String, Int>)` is computed over
  `stallRepository.all()` filtered to `owner.type == SOLO && owner.id == player`, grouped by
  `stall.kind`. Single source for "how many stalls does this player personally own", used by **both**
  the auction gate and the new buyout gate so they count identically. (Mirrors the existing inline
  count in `AuctionLifecycleService`, which is replaced by a call to this.)

### Changed units

- **`StallBuyoutService.buyForOwner`** — inject `LimitResolutionService` + `StallOwnershipCounter`.
  Before the economy charge: `val c = counter.counts(payer); canClaim(payer, stall.kind, c.total,
  c.byKind[stall.kind] ?: 0)`. On `Rejected` → return `Result.Rejected(reason)` with a plain-English
  reason string (no charge, no ownership change), matching the existing `StallBuyoutService`/
  `SellOfferService` convention (rendered via `purchase_sign.msg.rejected`). `buyForGuild` is untouched.
- **`AuctionLifecycleService`** (settle path) — replace the inline `stallRepository.all().count{…}`
  + `DEFAULT_KIND` placeholder with `counter.counts(bid.bidder)` and the stall's real `kind`, so the
  per-kind cap applies on auction wins too.
- **`AdminCommands`** — add `@Subcommand("limit")` (`/em limit`), `@Permission` a player-facing node
  (reuse `enthusiamarket.stall.info` or add `enthusiamarket.limit.view` default true): render
  `effectiveLimits(player)` (total + per-kind, `∞` for `-1`/unlimited) against `counter.counts(player)`.
- **`build.gradle.kts`** — `node("enthusiamarket.admin.bypasslimit", default = Default.OP, …)`.
- **`en_US.yml`** — `limit.info.*` (for `/em limit`). Buyout rejections use plain-string
  `Result.Rejected` reasons, not lang keys.

### Data flow — a personal buyout

```text
player buys a stall (sign / /em stall buy) → StallBuyoutService.buyForOwner(stallId, payer, price)
  → counter.counts(payer)  →  (total, byKind)   [SOLO-owned only]
  → limits.canClaim(payer, stall.kind, total, byKind[stall.kind] ?: 0)
      → Rejected.TotalCapReached(cap) → Result.Rejected("Stall limit reached (cap)")  [no charge]
      → Rejected.KindCapReached(kind, cap) → Result.Rejected("Limit reached for <kind> stalls (cap)")  [no charge]
      → Allowed → proceed: economy.withdraw → set owner SOLO → persist
```

## 3. Error handling

- **Total / kind cap reached** → `Result.Rejected(reason)` (plain string, cap shown), rendered via
  `purchase_sign.msg.rejected`. Both **before** any economy charge or ownership write.
- **Admin bypass** (`enthusiamarket.admin.bypasslimit`) → `canClaim` short-circuits to `Allowed`.
- **`/em limit` from console** → players-only (existing key).
- **Player in no limit group** → unlimited (per the critical fix in §1). `/em limit` renders `∞`;
  no gate blocks them.

## 4. Testing (TDD where there is logic)

| Unit | Tests |
|---|---|
| `StallOwnershipCounter` | counts SOLO-owned only (guild + unowned excluded); groups by kind; empty for a player who owns none. |
| `StallBuyoutService.buyForOwner` | allowed claim proceeds + charges; `TotalCapReached` rejects with no charge/ownership change; `KindCapReached` likewise; admin-bypass holder proceeds. |
| `AuctionLifecycleService` settle | uses the real `stall.kind` (per-kind cap now applies); guild-owned stalls don't count toward the winner's total. |
| `/em limit` rendering | total + per-kind + `∞`; pure formatter. |
| `LimitResolutionService` (no-group fix) | a player in no granted group → `effectiveLimits` unlimited + `canClaim` Allowed; an empty `config.limits` → everyone unlimited (regression for the reject-all bug). |

`LimitResolutionService` is already unit-tested (TDD-210); SP4 extends it with the no-group fix.

## 5. Build order (tasks)

1. **Fix `effectiveLimits`**: a player in no applicable group (incl. empty `config.limits`) is
   unlimited, not capped at 0 (+ regression test). Protects every gate.
2. `StallOwnershipCounter` (`counts` over SOLO ownership, by kind) (+ test) — and refactor the auction
   path's inline count to use it.
3. Gate `StallBuyoutService.buyForOwner` with `canClaim` + reject results (+ test).
4. Wire the real `stall.kind` + kind-restricted count into the auction settle gate (+ test).
5. `/em limit` command + `limit.info.*` lang + `enthusiamarket.admin.bypasslimit` node
   (+ formatter test).
6. Final gate (`clean detekt test shadowJar`) + mark `docs/tasks.md`.
