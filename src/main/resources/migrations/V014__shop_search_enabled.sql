-- ItemShops parity SP2 — per-shop opt-in for /shop search. Default 1 (true):
-- shops are discoverable unless the owner toggles it off in the edit menu.
ALTER TABLE shop_items ADD COLUMN search_enabled INTEGER NOT NULL DEFAULT 1;
