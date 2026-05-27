-- REQ-260 — public sell offers. One open offer per stall at a time
-- (PRIMARY KEY on stall_id). The mutex with an open auction is enforced
-- at the service layer rather than via a constraint; auctions live in
-- a separate table that this migration intentionally doesn't touch.
--
-- Note on version: V008 is taken by the parallel feat/arm-port-member-
-- roster branch. This migration uses V009 so the two PRs can land in
-- either order without renumbering. If member-roster lands second, no
-- collision; if it lands first, V009 already follows.

CREATE TABLE IF NOT EXISTS sell_offers (
    stall_id     TEXT PRIMARY KEY,
    seller_uuid  TEXT NOT NULL,
    price        INTEGER NOT NULL CHECK (price > 0),
    created_at   INTEGER NOT NULL,
    FOREIGN KEY (stall_id) REFERENCES stalls(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_sell_offers_seller ON sell_offers(seller_uuid);
