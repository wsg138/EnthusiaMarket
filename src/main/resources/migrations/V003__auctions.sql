CREATE TABLE IF NOT EXISTS auctions (
    id TEXT PRIMARY KEY,
    stall_id TEXT NOT NULL,
    state TEXT NOT NULL,
    start_at INTEGER NOT NULL,
    end_at INTEGER NOT NULL,
    starting_bid INTEGER NOT NULL,
    high_bid_amount INTEGER,
    high_bidder TEXT,
    high_placed_at INTEGER,
    anti_snipe_sec INTEGER NOT NULL,
    FOREIGN KEY (stall_id) REFERENCES stalls(id)
);
CREATE INDEX IF NOT EXISTS idx_auctions_stall_id ON auctions(stall_id);
CREATE INDEX IF NOT EXISTS idx_auctions_state ON auctions(state);