-- V019__shop_stock_count.sql — denormalized container stock for async /shop search rendering.
ALTER TABLE shop_items ADD COLUMN stock_count INTEGER NOT NULL DEFAULT 0;
