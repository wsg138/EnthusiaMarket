DROP INDEX IF EXISTS idx_shop_guild;
ALTER TABLE shop_items DROP COLUMN guild_id;
ALTER TABLE shop_items DROP COLUMN creator_id;
