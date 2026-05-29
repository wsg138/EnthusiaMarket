-- Sign-shop direction (SELL = owner sells, BUY = owner buys).
-- Until V012 the PurchaseMenu only wired the BUY (owner-buys-from-public)
-- path even when the sign was [SELL], so player-buys-from-shop was
-- unreachable. This column stores what SignPlaceListener parsed off
-- line 1 so the GUI can route to executeSell vs executeBuy.
-- Default SELL = "owner sells items" — the typical sign-shop case;
-- preserves the meaning of legacy rows after upgrade.
ALTER TABLE shop_items ADD COLUMN direction TEXT NOT NULL DEFAULT 'SELL';
