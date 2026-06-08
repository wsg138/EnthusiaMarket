-- V018__shop_sell_material.sql — denormalized sell-item material for fast /shop search.
ALTER TABLE shop_items ADD COLUMN sell_material TEXT;
CREATE INDEX IF NOT EXISTS idx_shop_sell_material ON shop_items(sell_material);
