-- Sign-shop direction (BUY = owner-buys-from-public)
-- path even when the sign was [SELL], so player-buys-from-shop was
-- unreachable. This column stores what SignPlaceListener parsed off
-- line 1 so the GUI can route to executeSell vs executeBuy.
-- Default SELL = "owner sells items" — the typical sign-shop case;
-- preserves the meaning of legacy rows after upgrade.
-- CHECK constraint ensures only valid enum values are accepted.
ALTER TABLE shop_items
    ADD COLUMN direction TEXT NOT NULL DEFAULT 'SELL'
    CHECK (direction IN ('BUY', 'SELL'));
