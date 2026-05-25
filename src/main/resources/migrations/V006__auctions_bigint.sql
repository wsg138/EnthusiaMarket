CREATE TABLE IF NOT EXISTS auctions_new (
    id TEXT PRIMARY KEY,
    stall_id TEXT NOT NULL,
    state TEXT NOT NULL,
    start_at BIGINT NOT NULL,
    end_at BIGINT NOT NULL,
    starting_bid INTEGER NOT NULL,
    high_bid_amount INTEGER,
    high_bidder TEXT,
    high_placed_at BIGINT,
    anti_snipe_sec INTEGER NOT NULL,
    FOREIGN KEY (stall_id) REFERENCES stalls(id)
);
INSERT INTO auctions_new SELECT * FROM auctions;
DROP TABLE auctions;
ALTER TABLE auctions_new RENAME TO auctions;
CREATE INDEX IF NOT EXISTS idx_auctions_stall_id ON auctions(stall_id);
CREATE INDEX IF NOT EXISTS idx_auctions_state ON auctions(state);
