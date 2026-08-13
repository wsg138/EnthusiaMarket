-- ES-X03 / REQ-300..307: durable Staff moderation provider.
-- Existing migrations are immutable; this migration adds the revision and
-- reservation state needed to fence ownership/listing changes across runtimes.

ALTER TABLE stalls ADD COLUMN moderation_revision INTEGER NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS market_moderation_operations (
    operation_id TEXT PRIMARY KEY,
    target_uuid TEXT NOT NULL,
    case_id TEXT NOT NULL,
    stall_id TEXT NOT NULL,
    state TEXT NOT NULL,
    snapshot_json TEXT NOT NULL,
    snapshot_checksum TEXT NOT NULL,
    current_checksum TEXT,
    review_due_at INTEGER NOT NULL,
    recovery_until INTEGER NOT NULL,
    reviewer_uuid TEXT,
    detail TEXT NOT NULL,
    revision INTEGER NOT NULL DEFAULT 1,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    FOREIGN KEY (stall_id) REFERENCES stalls(id)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_market_moderation_case_stall
    ON market_moderation_operations(case_id, stall_id);
CREATE INDEX IF NOT EXISTS idx_market_moderation_target_state
    ON market_moderation_operations(target_uuid, state, updated_at);
CREATE INDEX IF NOT EXISTS idx_market_moderation_review
    ON market_moderation_operations(state, review_due_at);

CREATE TABLE IF NOT EXISTS market_moderation_locks (
    stall_id TEXT PRIMARY KEY,
    operation_id TEXT NOT NULL UNIQUE,
    acquired_at INTEGER NOT NULL,
    FOREIGN KEY (stall_id) REFERENCES stalls(id)
);

CREATE TABLE IF NOT EXISTS market_player_fences (
    player_uuid TEXT PRIMARY KEY,
    active_acquisition_id TEXT,
    acquisition_until INTEGER,
    revision INTEGER NOT NULL DEFAULT 0,
    updated_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS market_stall_blacklists (
    player_uuid TEXT PRIMARY KEY,
    status TEXT NOT NULL,
    expires_at INTEGER,
    case_id TEXT NOT NULL,
    operation_id TEXT NOT NULL,
    revision INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_market_blacklist_operation
    ON market_stall_blacklists(operation_id);
CREATE INDEX IF NOT EXISTS idx_market_blacklist_status_expiry
    ON market_stall_blacklists(status, expires_at);
