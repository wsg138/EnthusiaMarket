-- V027 — unfreeze shops on stalls that are NOT in a rent-penalty state.
--
-- Root cause (2026-08-01, stall39 report): shops get frozen when a stall
-- enters GRACE / EMERGENCY_AUCTIONING (RentCollectionService.freezeByStall),
-- but were never unfrozen when the stall recovered to OWNED. The only
-- unfreeze path in the codebase was recoverOrphanedEmergencyStalls()
-- (EMERGENCY_AUCTIONING orphans). So any stall that paid its way out of
-- GRACE (StallRentExtensionService.extend), won an auction settlement
-- (AuctionLifecycleService.settleWithWinner), or was bought out
-- (StallBuyoutService) kept every shop permanently frozen — trades blocked
-- with "frozen" even though the stall sign showed a healthy rent countdown.
--
-- Invariant this migration enforces: shops are frozen IFF their stall is in
-- a penalty/auction state (GRACE, EMERGENCY_AUCTIONING, AUCTIONING,
-- RE_AUCTIONING). OWNED and UNOWNED stalls must have tradable shops.
--
-- This is a one-time data fix. The ShopFreezeStateListener (added in the
-- same patch) enforces the invariant going forward, so this must not recur.

UPDATE shop_items
SET frozen = 0
WHERE frozen = 1
  AND stall_id IN (
      SELECT id FROM stalls
      WHERE state IN ('OWNED', 'UNOWNED')
  );
