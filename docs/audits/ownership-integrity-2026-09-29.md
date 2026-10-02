# EnthusiaMarket 26.2 — Ownership Integrity Audit

**Date:** 2026-09-29  
**Baseline:** `main@8d04bd9` (PR #194 merged after 26.2 PR #193)  
**Scope:** stall ownership transitions, shops, delegated members, WorldGuard access, emergency auctions, ownership limits, moderation fencing, and the supplied read-only production snapshot.

## Summary

The architecture is sound enough to harden in place. The failures are consistency gaps between otherwise reasonable subsystems, not evidence that EnthusiaMarket needs a rewrite.

| ID | Severity | Confidence | Finding |
| --- | --- | --- | --- |
| OI-001 | Major | High | Emergency-auction rows still consume the former player's ownership limit. |
| OI-002 | Major | High | Stale previous-owner shop rows block the new owner from breaking inherited sign/container blocks. |
| OI-003 | Major | High | Sell-offer ownership transfer does not clean shops or resync region ownership. |
| OI-004 | Major | High | `Stall.awardTo` retains delegated members from the previous ownership context. |
| OI-005 | Major | High | Emergency-auction creation deliberately preserves defaulter shops, contradicting the older clean-auction intent. |
| OI-006 | Major | High | Emergency no-bid/orphan recovery performs only a partial UNOWNED reset. |
| OI-007 | Positive control | High | WorldGuard `setOwner` already clears old owners/members; do not rewrite it as the root cause. |
| OI-008 | Release risk | High | Any generic cleanup can break PR #194 exact moderation restoration if moderation state is not fenced first. |

## Production-snapshot evidence

The supplied production snapshot was inspected read-only; no live database mutation was performed.

A query identified 163 non-admin shop rows whose player owner did not match the current stall owner reference. This strongly supports the stale-transfer report but is not, by itself, a safe repair predicate because guild ownership uses a different identifier domain.

Credential values from the supplied configuration are intentionally omitted from this document. Production secret material must not be copied into commits, logs, test fixtures, or PR descriptions.
## Finding detail

### OI-001 — Ghost ownership

`StallOwnershipCounter.kt:14-18` counts every SOLO row matching the UUID, regardless of lifecycle state. `EMERGENCY_AUCTIONING` therefore still consumes cap even though the auction lifecycle treats the old claim as forfeited.

**Required behavior:** REQ-314; only OWNED and GRACE count.

### OI-002 — Stale shop protection

`BlockProtectionListener.kt:35-57` allows normal sign deletion only for `Shop.owner`; `BlockProtectionListener.kt:96-104` applies the same concept to linked containers.

When an ownership transfer leaves old shop rows behind, the new Stall owner is not considered owner of those shop protections.

**Required behavior:** REQ-316 plus conservative reconciliation under REQ-321.

### OI-003 — Sell-offer transfer misses cross-system cleanup

`SellOfferService.kt:156-165` awards and saves the Stall but does not remove previous-owner shops or call `RegionMemberSync`.

The acquisition permit introduced by PR #194 already wraps `purchase` and must remain.

**Required behavior:** REQ-316, REQ-317, REQ-320.

### OI-004 — Delegated member inheritance

`Stall.kt:94-103` does not reset `members` in `awardTo`.

**Required behavior:** REQ-315.
### OI-005 — Emergency auction preserves shops by design today

`RentCollectionService.kt:156-182` explicitly says the winner inherits all bound shops. The older draft REQ-280 recorded the opposite confirmed intent: restore the stall when emergency auction starts and auction it clean.

**Required behavior:** REQ-319 supersedes that cleanup detail while preserving former-owner identity only if settlement/recovery still needs it.

### OI-006 — Partial UNOWNED recovery

`RentCollectionService.kt:211-225` and `AuctionLifecycleService.closeWithoutAward` clear state/owner but do not consistently clear ownerSince, winningBid, members, nextRentAt, shops, region access, or other previous-owner residue.

`StallEvictionService.kt:49-87` already demonstrates the stronger invariant and should be used as behavioral reference, not blindly copied into moderation.

**Required behavior:** REQ-318.

### OI-007 — WorldGuard adapter is not the overlap source

`WorldGuardRegionMemberSync.kt:35-45` already removes existing owners and members before adding the new SOLO owner. Guild synchronization also clears first.

The release fix should cover missing lifecycle calls rather than replace working adapter semantics.

### OI-008 — PR #194 compatibility hazard

The moderation provider owns exact snapshots, revisions, durable locks, acquisition fences, shop-freeze state, and exact restoration. A generic ownership helper that clears data during PREPARED or MODERATION_HOLD would violate REQ-306..313.

**Required behavior:** REQ-320; characterization tests are the first implementation task.
## Release gates

Ownership hardening is not ready to merge until all of the following are true:

1. PR #194 characterization tests pass before and after the ownership changes.
2. Every new behavioral requirement has a red test before implementation.
3. Full test, detekt, architecture/Konsist, and shadow JAR builds pass.
4. Disposable migration tests prove reconciliation is idempotent and preserves guild/admin/moderation cases.
5. A copied production database passes the reconciliation scan before any production deployment.
6. The original production database is never used as the first migration test.
7. No production secret appears in source, fixtures, CI output, or PR text.
8. Manual validation covers sell-offer transfer, normal auction award, emergency auction award/no-bid recovery, eviction, and moderation prepare/hold/restore.

## Decision

Proceed with the SPEAR milestone defined by REQ-314..322. Do not rewrite EnthusiaMarket. Centralize ordinary ownership invariants incrementally behind tests, and leave PR #194's moderation subsystem as a protected specialized boundary.
