-- ItemShops parity SP6 — shop trade log. Powers /shop history, owner sale
-- notifications (notified flag = the unseen queue), and the sales_unseen placeholder.
CREATE TABLE IF NOT EXISTS shop_transactions (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    shop_id      INTEGER NOT NULL,
    owner        TEXT NOT NULL,
    buyer        TEXT NOT NULL,
    direction    TEXT NOT NULL,
    item         TEXT NOT NULL,
    quantity     INTEGER NOT NULL,
    total_price  INTEGER NOT NULL,
    created_at   INTEGER NOT NULL,
    notified     INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_shop_tx_owner ON shop_transactions(owner);
CREATE INDEX IF NOT EXISTS idx_shop_tx_created ON shop_transactions(created_at);
