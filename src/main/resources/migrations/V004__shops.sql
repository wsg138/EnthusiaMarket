CREATE TABLE IF NOT EXISTS shop_items (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    stall_id TEXT NOT NULL,
    owner TEXT NOT NULL,

    -- Sign position
    sign_world TEXT NOT NULL,
    sign_x INTEGER NOT NULL,
    sign_y INTEGER NOT NULL,
    sign_z INTEGER NOT NULL,

    -- Container position
    container_world TEXT NOT NULL,
    container_x INTEGER NOT NULL,
    container_y INTEGER NOT NULL,
    container_z INTEGER NOT NULL,

    -- Trade items (base64 serialized ItemStacks)
    sell_item TEXT NOT NULL,
    sell_amount INTEGER NOT NULL DEFAULT 1,
    cost_item TEXT NOT NULL,
    cost_amount INTEGER NOT NULL DEFAULT 1,

    -- Features
    trusted TEXT NOT NULL DEFAULT '',
    hopper_allow_in INTEGER NOT NULL DEFAULT 1,
    hopper_allow_out INTEGER NOT NULL DEFAULT 1,
    frozen INTEGER NOT NULL DEFAULT 0,
    admin_shop INTEGER NOT NULL DEFAULT 0,

    created_at TEXT NOT NULL DEFAULT (datetime('now')),
    updated_at TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_shop_sign ON shop_items(sign_world, sign_x, sign_y, sign_z);
CREATE INDEX IF NOT EXISTS idx_shop_container ON shop_items(container_world, container_x, container_y, container_z);
CREATE INDEX IF NOT EXISTS idx_shop_stall ON shop_items(stall_id);
CREATE INDEX IF NOT EXISTS idx_shop_owner ON shop_items(owner);