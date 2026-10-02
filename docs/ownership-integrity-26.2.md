# EnthusiaMarket 26.2 Ownership Integrity

**Date:** 2026-09-29  
**Requirements:** REQ-314 through REQ-322  
**Moderation baseline:** PR #194 / REQ-306 through REQ-313

This document is the operator and developer reference for the 26.2 ownership-integrity hardening. The database remains authoritative for stall ownership. WorldGuard, shop protection, IP reservations, and other runtime structures are projections of that authoritative state.

## Ownership invariants

The following rules apply to ordinary market ownership transitions:

1. Only `OWNED` and `GRACE` count as actively held stalls for a player's personal ownership limit.
2. Awarding a stall to a different owner starts a new delegated-member context; previous stall members do not carry over.
3. Previous-owner non-admin shops do not transfer to the successor owner. Administrative shops are preserved unless an administrative workflow explicitly removes them.
4. Successful ordinary ownership awards rebuild WorldGuard ownership through `RegionMemberSync`; the WorldGuard adapter replaces old owners/members instead of appending to them.
5. A canonical `UNOWNED` stall has:
   - `owner_type = NONE`;
   - an empty owner id;
   - no `owner_since`;
   - `winning_bid = 0`;
   - no delegated members;
   - no `next_rent_at`;
   - no previous-owner non-admin shops;
   - no effective WorldGuard owner/member projection.
6. `EMERGENCY_AUCTIONING` does not consume the former owner's normal stall limit. The former owner may remain on the stall row only as auction/seller provenance and receives no delegated shop or region access.
7. Emergency forfeiture is clean: after the authoritative state save and emergency-auction insert both succeed, the previous owner's stall IP reservation is released, non-admin shops are removed, WorldGuard ownership/members are cleared, and schematic restore is attempted when enabled.
8. Destructive projection cleanup happens only after the authoritative database write succeeds. Repository moderation/revision fencing therefore wins a race without shop/WG/IP data being destroyed first.

## Staff Market / moderation boundary

The Staff Market integration from PR #194 remains authoritative for moderation workflows.

While a stall has a durable moderation reservation or is in `MODERATION_HOLD`:

- ordinary acquisition, eviction, rent enforcement, and recovery paths honor `MarketMutationGate`;
- `moderation_revision` remains the persistence-level defense against stale concurrent writes;
- exact ownership/member/timing/shop-freeze snapshots remain under the moderation provider;
- generic ownership cleanup must not reinterpret or erase moderation recovery data;
- PREPARED / MODERATION_HOLD / RESTORED / RELEASED / QUARANTINED semantics are unchanged.

This is intentional duplication of protection: application-layer gates avoid side effects early, while repository revision/lock fencing remains defense in depth.

## V029 legacy ownership reconciliation

`V029__ownership_integrity_reconciliation.sql` is a forward, data-only reconciliation migration. Existing migrations remain immutable.

V029 repairs only state that can be proven stale from the authoritative stall row.

### V029 removes or normalizes

- Non-admin shops on active SOLO `OWNED` or `GRACE` stalls only when the shop owner UUID matches neither the current stall owner nor any current delegated stall member.
- Non-admin shops on true `UNOWNED` stalls.
- Non-admin shops on `EMERGENCY_AUCTIONING` stalls.
- Non-admin shops on system `AUCTIONING` / `RE_AUCTIONING` stalls whose owner type is `NONE`.
- Delegated member rosters on vacant, system-auction, and emergency-auction states where those members can no longer represent effective ownership.
- Stale ownership/timing fields on true `UNOWNED` rows.

### V029 deliberately preserves

- Administrative shops.
- Active guild-owned stall/shop data; guild IDs are never compared to player UUIDs as if they were the same identifier type.
- `MODERATION_HOLD` rows.
- Any stall with a live `market_moderation_locks` reservation.
- Ambiguous data that cannot be proven stale.
- WorldGuard itself. SQL cannot safely mutate that external projection.

The migration is idempotent. Reapplying its statements produces the same resulting data.

A copied production database was used for validation before release: 62 provably stale non-admin shops were removed, 41 legitimate delegated-member shops that the earlier owner-only predicate would have removed were preserved, 69 active guild shops were preserved, 3 stale member rosters were cleared, 2 stale UNOWNED rows were normalized, SQLite integrity remained OK, and a second reconciliation pass produced the same data state.

## WorldGuard reconciliation

V029 repairs database state only. After deployment, run the existing DB-authoritative region reconciliation:

```text
/em rg resync
```

This rebuilds WorldGuard ownership/member projections from the current stall records. It is the supported repair path if a database transition succeeded but a WorldGuard mutation previously failed.

## Administrative bulk rent extension

Command:

```text
/em rent extendall <duration>
```

Permission:

```text
enthusiamarket.admin.rent
```

Accepted durations are strict positive whole numbers with one lowercase suffix:

- `30m`
- `12h`
- `7d`

Zero, negative, decimal, unsupported-unit, and malformed values are rejected before the application service is invoked.

The command is an administrative deadline credit, not a rent payment:

- it performs no economy withdrawal or deposit;
- only `OWNED` and `GRACE` stalls are eligible;
- moderation-locked stalls and every other lifecycle state are skipped;
- the duration is added to the current `nextRentAt`;
- when `nextRentAt` is null, the legacy due-date estimate `ownerSince + collection interval` is used when available, falling back to the supplied current time only when no owner timestamp exists;
- one stall save failure does not abort the rest of the batch;
- a GRACE stall whose shifted deadline is now in the future returns to `OWNED` through the normal state-change event, allowing the existing shop-freeze listener to unfreeze its shops.

The command reports:

- `updated`: successfully shifted deadlines;
- `recovered`: GRACE stalls restored to OWNED;
- `skipped`: ineligible or moderation-locked stalls;
- `failed`: eligible stalls whose save failed.

## Deployment checklist

1. Put the market/server into the normal maintenance posture used for schema-affecting releases.
2. Back up the production market database before replacing the JAR.
3. Deploy the 26.2 ownership-integrity build.
4. Start EnthusiaMarket and allow the normal migration runner to apply V029.
5. Check startup logs for migration or moderation-conflict errors.
6. Run `/em rg resync` so WorldGuard reflects the reconciled authoritative database.
7. Spot-check:
   - a transferred SOLO stall;
   - an emergency-auction stall;
   - a guild-owned stall;
   - an admin shop;
   - a Staff Market reserved/held stall.
8. Use `/em rent extendall <duration>` only when an administrative deadline credit is actually desired; deploying the ownership repair does not require extending rent.

For a full rollback after V029 has changed production data, restore the pre-deployment database backup as well as the previous JAR. V029 is intentionally forward-only and is not undone by merely downgrading the plugin binary.
