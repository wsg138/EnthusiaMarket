# Guild Rent Collection (audit M11)

**Date:** 2026-06-04
**Status:** Approved (brainstorming complete)
**Owner:** BadgersMC
**Plugin:** EnthusiaMarket (EM)

## 0. Context

The pre-release audit (M11) found that **guild-owned stalls pay no rent — forever**:
`RentCollectionService.processStall` skips any owner that isn't `SOLO` (the comment reads "guild bank
integration not yet available"). A guild can therefore hold unlimited stalls rent-free, with no
default/eviction pressure — an economy balance hole.

This feature collects rent on **GUILD-owned** stalls from the **guild bank**, running the same
default → grace → eviction lifecycle as personal stalls. Decided: guild rent draws the **guild bank**
for both automatic collection and voluntary rent-extension.

## 1. Scope

- **Automatic rent collection** charges guild stalls via `GuildProvider.bankWithdraw`. On failure the
  stall follows the existing path: `OWNED → GRACE`, then `GRACE → evict` once the grace window
  elapses. On success the rent timer advances (and `GRACE → OWNED` restores).
- **Voluntary rent-extension** (`/em`-sign click on a guild stall) also draws the guild bank, and its
  failure-refund returns to the bank.

### Out of scope
- Notifying the guild on default/eviction (no rent-notification system exists in EM).
- A separate guild rent rate, or partial/proportional guild payments.
- Adding refund-on-persist-failure to *automatic* collection (it doesn't exist for the SOLO path
  either — see the parity note).

## 2. Architecture

The grace/eviction logic is **state-based** (keyed on `StallState`, not owner type), so it is reused
unchanged. The only behavioural difference is the **charge source**.

### Changed units

- **`RentCollectionService`** (`application/`) — inject `GuildProvider`. In `processStall`:
  - Replace the `if (stall.owner.type != OwnerType.SOLO) return Skipped` guard with a `NONE`-only skip
    (a `NONE` owner on an active stall is a data inconsistency; nothing to charge).
  - Compute `rentDue` (the existing M4-floored value), then charge via a new private
    `chargeRent(stall, rentDue): Boolean`:
    - `SOLO` → `economy.withdraw(UUID.fromString(owner.id), rentDue)` (unchanged behaviour; invalid
      UUID → treat as skip).
    - `GUILD` → `guildProvider.bankWithdraw(owner.id, rentDue)` (`owner.id` is the guild id string).
    - `rentDue <= 0` (admin-gifted, `winningBid <= 0`) → no charge, treated as collected (advance
      timer), matching the SOLO no-charge path.
  - Feed `chargeRent`'s boolean into the existing success/failure branches verbatim — grace, eviction,
    WG clear, offer cleanup, and `StallStateChangedEvent` are untouched.

- **`StallRentExtensionService.extend`** (`application/`) — already injects `GuildProvider` and gates
  on `Stall.canManage` (a guild member with `MANAGE_SHOPS` may extend). Branch the **charge** and the
  **M5 refund** by owner type:
  - `SOLO` → `economy.withdraw(actor, amount)` / refund `economy.deposit(actor, amount)` (unchanged).
  - `GUILD` → `guildProvider.bankWithdraw(owner.id, amount)` / refund `guildProvider.bankDeposit(owner.id, amount)`.
  - Insufficient funds → `Result.Rejected` with a guild-specific message for guild stalls.
  - The refund still checks the boolean return (per the #38 hardening) and rethrows the original
    persistence exception.

### Parity note (intentional)
Automatic collection's SOLO path does **not** refund if `stallRepository.save` throws after a
successful withdraw (pre-existing; not flagged by the audit). The guild path **mirrors this exactly**
— no new refund logic in auto-collection — so both owner types behave identically. The voluntary
`extend` path *does* refund, and that branch is extended to the guild bank.

### Data flow — automatic guild rent

```text
scheduler tick → processStall(guild stall)
  → rentDue (M4-floored)
  → chargeRent: guildProvider.bankWithdraw(guildId, rentDue)
      → true  → advance nextRentAt (GRACE → OWNED if needed) → Collected
      → false → OWNED → GRACE (start grace timer) → Defaulted
                GRACE + past grace window → evict (UNOWNED, clear WG + offers) → Evicted
```

## 3. Error handling

- **Guild bank insufficient** → automatic: GRACE then evict (as personal). Voluntary extend →
  `Result.Rejected("The guild bank has insufficient funds: <amount> required")`.
- **`NONE`-owner active stall** → skipped (data inconsistency; logged at most).
- **`rentDue <= 0`** → no charge, treated as collected (admin-gifted regions don't surprise-bill).
- **Voluntary extend persist failure** → refund the guild bank (`bankDeposit`); the original
  persistence exception is rethrown; a failed refund logs "manual refund required".

## 4. Testing (TDD)

| Unit | Tests |
|---|---|
| `RentCollectionService` (guild) | guild stall charged via `bankWithdraw`, success advances `nextRentAt`; `bankWithdraw` false on OWNED → GRACE; on GRACE past window → evicted (`UNOWNED`, ownership cleared); `NONE` owner → skipped. |
| `StallRentExtensionService` (guild) | guild extend draws the bank (`bankWithdraw`); insufficient → `Rejected`; persist failure refunds the bank (`bankDeposit`) and rethrows. |

Existing SOLO tests must stay green (the charge branch is owner-type-gated).

## 5. Build order (tasks)

1. `RentCollectionService`: inject `GuildProvider`, replace the non-SOLO skip with a `chargeRent`
   branch (SOLO economy / GUILD bank), `NONE` skip, `rentDue <= 0` no-charge (+ guild tests).
2. `StallRentExtensionService`: branch charge + refund by owner type (+ guild tests).
3. Lang: guild-bank insufficient message for `extend`.
4. Final gate (`clean detekt test shadowJar`) + mark M11 closed in `docs/tasks.md`.
