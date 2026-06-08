-- V017__guild_trade_policies.sql — per-guild-pair tariff/embargo rules (guild trade policies)
CREATE TABLE IF NOT EXISTS guild_trade_policies (
    owner_guild_id  TEXT NOT NULL,
    target_guild_id TEXT NOT NULL,
    kind            TEXT NOT NULL,            -- TARIFF | EMBARGO
    rate_pct        INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (owner_guild_id, target_guild_id)
);
CREATE INDEX IF NOT EXISTS idx_gtp_owner ON guild_trade_policies(owner_guild_id);
-- target index so deleteAllInvolving (owner = ? OR target = ?) doesn't scan on the target half
CREATE INDEX IF NOT EXISTS idx_gtp_target ON guild_trade_policies(target_guild_id);
