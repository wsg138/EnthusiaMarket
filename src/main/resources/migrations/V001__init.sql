CREATE TABLE IF NOT EXISTS stalls (
    id              TEXT PRIMARY KEY,
    region_id       TEXT NOT NULL,
    world           TEXT NOT NULL,
    state           TEXT NOT NULL,
    owner_type      TEXT NOT NULL,
    owner_id        TEXT NOT NULL,
    owner_since     INTEGER,
    winning_bid     INTEGER NOT NULL DEFAULT 0,
    rent_mode       TEXT NOT NULL,
    rent_pct        REAL NOT NULL DEFAULT 0,
    rent_flat       INTEGER NOT NULL DEFAULT 0,
    UNIQUE(world, region_id)
);

CREATE INDEX IF NOT EXISTS idx_stalls_state ON stalls(state);

CREATE TABLE IF NOT EXISTS auctions (
    id              TEXT PRIMARY KEY,
    stall_id        TEXT NOT NULL,
    state           TEXT NOT NULL,
    start_at        INTEGER NOT NULL,
    end_at          INTEGER NOT NULL,
    starting_bid    INTEGER NOT NULL,
    high_bid_amount INTEGER,
    high_bidder     TEXT,
    high_placed_at  INTEGER,
    anti_snipe_sec  INTEGER NOT NULL,
    FOREIGN KEY (stall_id) REFERENCES stalls(id)
);

CREATE INDEX IF NOT EXISTS idx_auctions_stall_state ON auctions(stall_id, state);

CREATE TABLE IF NOT EXISTS bids (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    auction_id      TEXT NOT NULL,
    bidder_uuid     TEXT NOT NULL,
    amount          INTEGER NOT NULL,
    placed_at       INTEGER NOT NULL,
    FOREIGN KEY (auction_id) REFERENCES auctions(id)
);

CREATE TABLE IF NOT EXISTS signs (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    stall_id        TEXT NOT NULL,
    direction       TEXT NOT NULL,
    item_key        TEXT NOT NULL,
    price           INTEGER NOT NULL,
    sign_location   TEXT NOT NULL UNIQUE,
    container_loc   TEXT NOT NULL,
    FOREIGN KEY (stall_id) REFERENCES stalls(id)
);

CREATE TABLE IF NOT EXISTS sales_ledger (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    stall_id        TEXT NOT NULL,
    sign_id         INTEGER NOT NULL,
    buyer_uuid      TEXT NOT NULL,
    gross           INTEGER NOT NULL,
    tax             INTEGER NOT NULL,
    net             INTEGER NOT NULL,
    occurred_at     INTEGER NOT NULL,
    FOREIGN KEY (stall_id) REFERENCES stalls(id),
    FOREIGN KEY (sign_id) REFERENCES signs(id)
);

CREATE INDEX IF NOT EXISTS idx_sales_stall_time ON sales_ledger(stall_id, occurred_at);

CREATE TABLE IF NOT EXISTS rent_ledger (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    stall_id        TEXT NOT NULL,
    charged_at      INTEGER NOT NULL,
    amount          INTEGER NOT NULL,
    paid            INTEGER NOT NULL DEFAULT 0,
    payer_type      TEXT NOT NULL,
    payer_id        TEXT NOT NULL,
    FOREIGN KEY (stall_id) REFERENCES stalls(id)
);

CREATE INDEX IF NOT EXISTS idx_rent_stall_time ON rent_ledger(stall_id, charged_at);

CREATE TABLE IF NOT EXISTS grace_events (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    stall_id        TEXT NOT NULL,
    started_at      INTEGER NOT NULL,
    expires_at      INTEGER NOT NULL,
    cured_at        INTEGER,
    FOREIGN KEY (stall_id) REFERENCES stalls(id)
);

CREATE TABLE IF NOT EXISTS favorites (
    player_uuid     TEXT NOT NULL,
    stall_id        TEXT NOT NULL,
    favorited_at    INTEGER NOT NULL,
    PRIMARY KEY (player_uuid, stall_id),
    FOREIGN KEY (stall_id) REFERENCES stalls(id)
);

CREATE TABLE IF NOT EXISTS schema_version (
    version         INTEGER PRIMARY KEY,
    applied_at      INTEGER NOT NULL
);
