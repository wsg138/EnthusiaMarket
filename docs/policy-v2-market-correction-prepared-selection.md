# Policy v2 market targeted-stall prepared selection

**Read-only, non-durable prerequisite.** This code extends the existing [Market PR #15](https://github.com/wsg138/EnthusiaMarket/pull/15) targeted-stall preflight and **does not** execute any stall ownership removal, delete a claim, send mail, move items, or emit a commit receipt.

`MarketStallCorrectionPreparedSelection.prepare(request, providerStalls)` validates an operation UUID, reviewer identity, Staff case identifier, positive case revision, subject, exact stall ID/world/revision, and preparation timestamp, then applies the existing fail-closed ownership, completeness and lock preflight. This records a **read-only in-memory expected stall**, not trusted authorization.

`Prepared.revalidate(freshProviderStalls)` runs the provider preflight again and requires complete record equality. This catches status changes (such as `OWNED` → `GRACE`) with an unchanged numeric revision, in addition to stale revisions, changed ownership, locks, missing or duplicate stalls and oversized provider snapshots. Changes to unrelated owned stalls need not invalidate the selected exact target.

## What remains before a mutation API

- This class does not authenticate the caller, verify that Staff case revision against an authoritative case store, confirm a second reviewer, or persist a prepared operation.
- A caller can supply an untrusted list. The eventual provider must obtain a fresh authoritative JDBC snapshot and re-run all checks **inside the same transaction** that performs the actual ownership adjustment.
- Define atomic receipt and idempotent replay keyed by operation UUID, bound to reviewer and case revision, with unchanged unrelated inventory/lease/auction state.
- Add transaction isolation, lock contention, multi-step failure injection, mail/refund/payout side-effect recovery, startup replay and appeal/compensation tests. The deletion/ownership mutation endpoint must remain disabled until these tests pass.
- Market GitHub releases trigger from merges to `main`. **Do not merge or deploy without owner authorization and a release-impact review.**

No production changes, and no Policy v2 activation.

## CI verification

The Market build workflow runs only when a pull request targets `main`. The stacked pull request may temporarily target `main` to validate its complete head, including base PR #15; it must be restored to the preflight branch for a focused review. This does not authorize merging or releasing either PR.
