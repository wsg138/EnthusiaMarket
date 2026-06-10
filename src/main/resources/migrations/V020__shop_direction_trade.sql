-- V012 added `CHECK (direction IN ('BUY', 'SELL'))` before the TRADE direction
-- existed. SignDirection later grew a TRADE value (barter signs, see V016), but
-- the CHECK was never widened — so placing a [TRADE] sign writes
-- direction = 'TRADE' and SQLite rejects the insert with SQLITE_CONSTRAINT_CHECK,
-- crashing SignPlaceListener.
--
-- SQLite cannot ALTER or DROP a CHECK constraint in place, so we rebuild
-- shop_items with the widened constraint and copy every row across. Nothing
-- references shop_items via FOREIGN KEY, so the drop/rename is safe.

CREATE TABLE shop_items_new (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    stall_id TEXT NOT NULL,
    owner TEXT NOT NULL,

    sign_world TEXT NOT NULL,
    sign_x INTEGER NOT NULL,
    sign_y INTEGER NOT NULL,
    sign_z INTEGER NOT NULL,

    container_world TEXT NOT NULL,
    container_x INTEGER NOT NULL,
    container_y INTEGER NOT NULL,
    container_z INTEGER NOT NULL,

    sell_item TEXT NOT NULL,
    sell_amount INTEGER NOT NULL DEFAULT 1,
    cost_item TEXT NOT NULL,
    cost_amount INTEGER NOT NULL DEFAULT 1,

    trusted TEXT NOT NULL DEFAULT '',
    hopper_allow_in INTEGER NOT NULL DEFAULT 1,
    hopper_allow_out INTEGER NOT NULL DEFAULT 1,
    frozen INTEGER NOT NULL DEFAULT 0,
    admin_shop INTEGER NOT NULL DEFAULT 0,

    created_at TEXT NOT NULL DEFAULT (datetime('now')),
    updated_at TEXT NOT NULL DEFAULT (datetime('now')),

    guild_id VARCHAR(36),
    creator_id VARCHAR(36),

    direction TEXT NOT NULL DEFAULT 'SELL'
        CHECK (direction IN ('BUY', 'SELL', 'TRADE')),
    search_enabled INTEGER NOT NULL DEFAULT 1,
    sell_material TEXT,
    stock_count INTEGER NOT NULL DEFAULT 0
);

INSERT INTO shop_items_new (
    id, stall_id, owner,
    sign_world, sign_x, sign_y, sign_z,
    container_world, container_x, container_y, container_z,
    sell_item, sell_amount, cost_item, cost_amount,
    trusted, hopper_allow_in, hopper_allow_out, frozen, admin_shop,
    created_at, updated_at,
    guild_id, creator_id,
    direction, search_enabled, sell_material, stock_count
)
SELECT
    id, stall_id, owner,
    sign_world, sign_x, sign_y, sign_z,
    container_world, container_x, container_y, container_z,
    sell_item, sell_amount, cost_item, cost_amount,
    trusted, hopper_allow_in, hopper_allow_out, frozen, admin_shop,
    created_at, updated_at,
    guild_id, creator_id,
    direction, search_enabled, sell_material, stock_count
FROM shop_items;

DROP TABLE shop_items;

ALTER TABLE shop_items_new RENAME TO shop_items;

CREATE INDEX IF NOT EXISTS idx_shop_sign ON shop_items(sign_world, sign_x, sign_y, sign_z);
CREATE INDEX IF NOT EXISTS idx_shop_container ON shop_items(container_world, container_x, container_y, container_z);
CREATE INDEX IF NOT EXISTS idx_shop_stall ON shop_items(stall_id);
CREATE INDEX IF NOT EXISTS idx_shop_owner ON shop_items(owner);
