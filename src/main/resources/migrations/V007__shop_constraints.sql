-- Add unique index on sign coordinates to prevent duplicate shops on the same sign
CREATE UNIQUE INDEX IF NOT EXISTS idx_shop_sign_unique ON shop_items(sign_world, sign_x, sign_y, sign_z);
