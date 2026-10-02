# EnthusiaMarket 26.2 Ownership Integrity — Design

**Date:** 2026-09-29  
**Baseline:** `main@8d04bd9` — PR #194 Staff Market integration, after PR #193 26.2 compatibility  
**SPEAR scope:** REQ-314 through REQ-322  
**Status:** Implemented in this PR; completion evidence is tracked in `docs/tasks.md`.

## Goal

Harden ownership transitions so a stall has one effective current ownership context across the Stall aggregate, delegated members, shop rows, WorldGuard ACLs, rent state, auction state, and acquisition limits without changing the moderation semantics introduced by PR #194.

The release fix must solve the reported previous-owner overlap and ghost-ownership failures, reconcile provably stale production data, and add an administrative way to credit time to all actively held stalls.

## Protected baseline

PR #194 is part of the source of truth. The following behavior is explicitly protected:

- moderation revisions on `stalls`;
- durable `market_moderation_locks` and player acquisition fences;
- PREPARED / MODERATION_HOLD / RESTORED / RELEASED / QUARANTINED lifecycle;
- exact moderation snapshots and checksum validation;
- exact shop-freeze restoration;
- `MarketRegionAccessCoordinator` clear/rebuild behavior;
- acquisition rejection before money or ownership changes.

Generic ownership cleanup must not be invoked inside a live moderation reservation or hold unless the moderation workflow itself requests that transition.
## Confirmed findings

### OI-001 — Ghost ownership limit

`StallOwnershipCounter.counts` filters only by SOLO owner identity and ignores `StallState` (`StallOwnershipCounter.kt:14-18`). A player therefore continues to consume a stall slot while their row is `EMERGENCY_AUCTIONING`, even though the emergency-auction lifecycle says the former owner has lost the claim.

### OI-002 — Previous-owner shop protection survives transfer

`BlockProtectionListener` authorizes sign/container break from `Shop.owner`, not current stall ownership (`BlockProtectionListener.kt:35-57,96-104`). If a transfer leaves old shop rows behind, the new stall owner cannot remove the inherited sign/container through normal ownership rights.

The read-only production snapshot supplied on 2026-09-29 contained 163 non-admin shop rows whose player owner did not match the current stall owner reference. That raw mismatch count is evidence of stale data, not deletion authority: guild owner IDs are not player UUIDs and must be handled separately.

### OI-003 — Direct sell offers update the Stall row only

`SellOfferService.persistOwnershipTransfer` calls `stall.awardTo(...)` and `stalls.save(updated)` but does not remove the seller's shops or invoke `RegionMemberSync` (`SellOfferService.kt:156-165`). PR #194 acquisition fencing already wraps this path and must remain intact.

### OI-004 — Domain award carries delegated members

`Stall.awardTo` replaces state, owner, ownerSince, winningBid, and nextRentAt but leaves `members` unchanged (`Stall.kt:94-103`). A successor owner can therefore inherit the prior owner's delegated stall-member roster.

### OI-005 — Emergency auction intentionally preserves old shops today

`RentCollectionService.emergencyAuction` explicitly states that it does not delete shops or clear WG because the winner inherits all bound shops (`RentCollectionService.kt:156-182`). This conflicts with the previously documented REQ-280 intent that an emergency-auction stall is restored and auctioned clean.
### OI-006 — Emergency no-bid/orphan release is partial

`recoverOrphanedEmergencyStalls` and `AuctionLifecycleService.closeWithoutAward` set `state = UNOWNED` and clear the owner, but leave other ownership-era fields and cleanup responsibilities to chance. The existing `StallEvictionService` is the stronger reference: it clears ownerSince, winningBid, members, nextRentAt, shops, WG ACL, IP ownership, and restores the schematic.

### OI-007 — WorldGuard adapter itself is already correct

`WorldGuardRegionMemberSync.setOwner` removes all previous owners and members before adding the new owner (`WorldGuardRegionMemberSync.kt:35-45`). The design must not rewrite this adapter as if it were the source of overlap. The defect is lifecycle coverage: some ownership-transfer paths never call it.

## Ownership invariants

1. Only `OWNED` and `GRACE` represent an actively held stall for ownership limits.
2. A change to a different owner starts with an empty delegated-member set.
3. Previous-owner non-admin shops do not transfer to a successor owner.
4. Administrative shops survive ordinary ownership transfer unless an administrative workflow explicitly removes them.
5. Every successful ordinary ownership award rebuilds region access through `RegionMemberSync`.
6. A true `UNOWNED` stall has no owner, ownerSince, winningBid, delegated members, nextRentAt, previous-owner non-admin shops, or WG ACL.
7. `EMERGENCY_AUCTIONING` may retain former-owner identity only as settlement/recovery provenance; it grants no active ownership slot, delegated access, or inherited shop rights.
8. Moderation reservations/holds are governed by REQ-306..313 and are not generic ownership transitions.
9. Legacy reconciliation repairs only provably stale state and is safe to repeat.

## Transition matrix

| Transition | Members | Non-admin shops | WorldGuard | Rent/timing | Moderation |
| --- | --- | --- | --- | --- | --- |
| UNOWNED → OWNED buyout | empty/new context | must start clean | replace with new owner/guild | fresh deadline | acquisition fence required |
| OWNED → OWNED sell offer | clear prior | delete prior context | rebuild for buyer | current sale semantics | acquisition fence required |
| auction → OWNED winner | clear prior | delete prior context | rebuild for winner | fresh deadline | award fence required |
| GRACE → EMERGENCY_AUCTIONING | remove delegated access | delete prior non-admin shops | remove prior access | stop active ownership | reservation rules still apply |
| emergency auction → OWNED | empty/new context | clean | rebuild for winner | fresh deadline | award fence required |
| emergency/no-bid → UNOWNED | empty | none | empty | null/reset | no live reservation |
| admin eviction → UNOWNED | existing good path | delete | clear | null/reset | existing admin behavior |
| MODERATION_HOLD / restore | PR #194 rules | PR #194 snapshot rules | PR #194 coordinator | exact snapshot | PR #194 authoritative |
## Architecture

Ordinary ownership transitions gain one application-layer boundary responsible for applying the invariants above. Existing use cases may keep their public APIs, but buyout, sell-offer, auction settlement, emergency recovery, and eviction must stop duplicating partial cleanup rules.

The shared boundary may compose existing domain ports/repositories rather than introducing Bukkit/JDBC dependencies into application code. The final shape is chosen during SPEAR prove/engine after characterization tests constrain the behavior.

The domain should expose explicit state operations instead of repeated partial `copy(...)` calls:

- award to a new owner with delegated members cleared;
- release to UNOWNED with ownership/timing fields reset;
- a reusable predicate for actively held ownership states.

The moderation provider remains separate. Its JDBC transaction, snapshots, checksums, revision fencing, and restoration semantics are deliberately stronger than ordinary transition cleanup and must not be routed through a lossy generic helper.

## Legacy-data reconciliation

Use a forward migration/reconciliation after V028; never edit applied migrations.

Repair is conservative:

- SOLO stale relationships may be repaired when the authoritative Stall owner/state proves the previous player context is obsolete;
- admin shops are preserved;
- guild rows are not compared to player UUIDs as if they were the same identifier type;
- stalls with active moderation reservations/holds are skipped;
- ambiguous rows are reported, not destroyed;
- rerunning the reconciliation produces no additional mutation.

## Bulk rent extension

`/em rent extendall <duration>` is an administrative deadline credit, not a rent payment. It charges nobody, acts only on `OWNED` and `GRACE`, shifts `nextRentAt`, and isolates per-stall failures. If a GRACE deadline becomes future again, the stall returns to OWNED through the normal state-change event so shop freeze behavior remains consistent.

## Out of scope

- Changing PR #194's moderation API contract.
- Rewriting WorldGuard membership storage.
- Transferring seller shop definitions to a buyer.
- Automatically deleting ambiguous guild/admin/moderation data.
- Broader market feature work unrelated to ownership integrity.
