-- ItemShops parity SP3 — per-owner barter profits vault. Payment items from [TRADE]
-- shops accumulate here, aggregated by NBT-serialized item key, withdrawn via /shopvault.
CREATE TABLE IF NOT EXISTS shop_vault (
    owner   TEXT NOT NULL,
    item    TEXT NOT NULL,
    amount  INTEGER NOT NULL CHECK (amount > 0),
    PRIMARY KEY (owner, item)
);
CREATE INDEX IF NOT EXISTS idx_shop_vault_owner ON shop_vault(owner);
