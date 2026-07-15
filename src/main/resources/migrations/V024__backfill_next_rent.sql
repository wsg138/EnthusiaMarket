-- Backfill next_rent_at for pre-V011 stalls so the purchase-sign
-- countdown and rent collection grace-window logic work correctly
-- without falling back to ownerSince (which is the original purchase
-- date, not the next rent deadline).
--
-- OWNED stalls: nextRentAt = ownerSince + 1 day (default collection
--   interval). The first rent tick will catch these up.
-- GRACE stalls: nextRentAt = ownerSince (rent already overdue, grace
--   window has been running). After backfill the tick will
--   evaluate immediately.
-- EMERGENCY_AUCTIONING: same as GRACE (already past default).
-- UNOWNED stalls: leave null (irrelevant).

UPDATE stalls
SET next_rent_at = owner_since + 86400000
WHERE state = 'OWNED' AND next_rent_at IS NULL AND owner_since IS NOT NULL;

UPDATE stalls
SET next_rent_at = owner_since
WHERE state IN ('GRACE', 'EMERGENCY_AUCTIONING') AND next_rent_at IS NULL AND owner_since IS NOT NULL;
