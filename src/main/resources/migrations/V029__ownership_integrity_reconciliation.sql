-- REQ-321: conservative ownership-integrity reconciliation.
--
-- This migration repairs only database state whose staleness can be proven
-- from the authoritative stall row. It deliberately does not infer guild
-- membership, touch admin shops, or mutate stalls reserved by Staff Market.
-- WorldGuard is an external projection and remains repairable through
-- /em rg resync after the database is authoritative.

-- 1. Remove non-admin shops that cannot belong to the effective ownership
-- context anymore:
--   * active SOLO stall, but the shop belongs to neither the owner nor a current delegated member;
--   * true vacant or emergency-auction stall;
--   * system auction/re-auction with no owner.
DELETE FROM shop_items
WHERE admin_shop = 0
  AND EXISTS (
      SELECT 1
      FROM stalls
      WHERE stalls.id = shop_items.stall_id
        AND stalls.state <> 'MODERATION_HOLD'
        AND NOT EXISTS (
            SELECT 1
            FROM market_moderation_locks
            WHERE market_moderation_locks.stall_id = stalls.id
        )
        AND (
            (
                stalls.owner_type = 'SOLO'
                AND stalls.state IN ('OWNED', 'GRACE')
                AND shop_items.owner <> stalls.owner_id
                -- Both values are canonical UUID strings. UUID tokens have
                -- fixed length, so INSTR cannot confuse one complete member UUID
                -- with another while remaining portable across SQLite/MariaDB.
                AND instr(stalls.members, shop_items.owner) = 0
            )
            OR stalls.state IN ('UNOWNED', 'EMERGENCY_AUCTIONING')
            OR (
                stalls.owner_type = 'NONE'
                AND stalls.state IN ('AUCTIONING', 'RE_AUCTIONING')
            )
        )
  );

-- 2. Delegated members are never meaningful once active ownership has been
-- forfeited or the stall belongs to the system auction pool. Emergency
-- auctions may retain owner identity only as seller provenance.
UPDATE stalls
SET members = ''
WHERE members <> ''
  AND state <> 'MODERATION_HOLD'
  AND NOT EXISTS (
      SELECT 1
      FROM market_moderation_locks
      WHERE market_moderation_locks.stall_id = stalls.id
  )
  AND (
      state IN ('UNOWNED', 'EMERGENCY_AUCTIONING')
      OR (
          owner_type = 'NONE'
          AND state IN ('AUCTIONING', 'RE_AUCTIONING')
      )
  );

-- 3. UNOWNED is a canonical vacant state. Preserve moderation_revision and
-- all unrelated configuration fields while removing stale ownership/timing
-- residue left by older partial release paths.
UPDATE stalls
SET owner_type = 'NONE',
    owner_id = '',
    owner_since = NULL,
    winning_bid = 0,
    members = '',
    next_rent_at = NULL
WHERE state = 'UNOWNED'
  AND NOT EXISTS (
      SELECT 1
      FROM market_moderation_locks
      WHERE market_moderation_locks.stall_id = stalls.id
  );
