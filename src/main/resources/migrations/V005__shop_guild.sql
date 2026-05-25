ALTER TABLE shop_items ADD COLUMN guild_id VARCHAR(36);
ALTER TABLE shop_items ADD COLUMN creator_id VARCHAR(36);
CREATE INDEX IF NOT EXISTS idx_shop_guild ON shop_items(guild_id);
