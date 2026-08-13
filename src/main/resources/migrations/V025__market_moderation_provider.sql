-- ES-X03 / REQ-300..307: durable Staff moderation provider.
-- Existing migrations are immutable; this migration adds the revision and
-- reservation state needed to fence ownership/listing changes across runtimes.

ALTER TABLE stalls ADD COLUMN moderation_revision BIGINT NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS market_moderation_operations (
    operation_id VARCHAR(36) PRIMARY KEY,
    target_uuid VARCHAR(36) NOT NULL,
    case_id VARCHAR(64) NOT NULL,
    stall_id VARCHAR(128) NOT NULL,
    state VARCHAR(32) NOT NULL,
    snapshot_json TEXT NOT NULL,
    snapshot_checksum CHAR(64) NOT NULL,
    current_checksum CHAR(64),
    review_due_at BIGINT NOT NULL,
    recovery_until BIGINT NOT NULL,
    reviewer_uuid VARCHAR(36),
    detail VARCHAR(512) NOT NULL,
    revision BIGINT NOT NULL DEFAULT 1,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    FOREIGN KEY (stall_id) REFERENCES stalls(id)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_market_moderation_case_stall
    ON market_moderation_operations(case_id, stall_id);
CREATE INDEX IF NOT EXISTS idx_market_moderation_target_state
    ON market_moderation_operations(target_uuid, state, updated_at);
CREATE INDEX IF NOT EXISTS idx_market_moderation_review
    ON market_moderation_operations(state, review_due_at);

CREATE TABLE IF NOT EXISTS market_moderation_locks (
    stall_id VARCHAR(128) PRIMARY KEY,
    operation_id VARCHAR(36) NOT NULL UNIQUE,
    acquired_at BIGINT NOT NULL,
    FOREIGN KEY (stall_id) REFERENCES stalls(id)
);

CREATE TABLE IF NOT EXISTS market_player_fences (
    player_uuid VARCHAR(36) PRIMARY KEY,
    active_acquisition_id VARCHAR(64),
    acquisition_until BIGINT,
    revision BIGINT NOT NULL DEFAULT 0,
    updated_at BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS market_stall_blacklists (
    player_uuid VARCHAR(36) PRIMARY KEY,
    status VARCHAR(16) NOT NULL,
    expires_at BIGINT,
    case_id VARCHAR(64) NOT NULL,
    operation_id VARCHAR(36) NOT NULL,
    revision BIGINT NOT NULL,
    updated_at BIGINT NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_market_blacklist_operation
    ON market_stall_blacklists(operation_id);
CREATE INDEX IF NOT EXISTS idx_market_blacklist_status_expiry
    ON market_stall_blacklists(status, expires_at);
