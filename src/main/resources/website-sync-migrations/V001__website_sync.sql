CREATE TABLE IF NOT EXISTS em_websync_state (
    state_key VARCHAR(64) PRIMARY KEY,
    state_value VARCHAR(512) NOT NULL
);
CREATE TABLE IF NOT EXISTS em_websync_stalls (
    stall_id VARCHAR(32) PRIMARY KEY,
    revision BIGINT NOT NULL,
    latest_hash VARCHAR(64) NOT NULL,
    pending_body LONGBLOB,
    pending_event_id VARCHAR(128),
    pending_since BIGINT,
    retry_at BIGINT,
    attempt_count INTEGER NOT NULL,
    acknowledged_revision BIGINT NOT NULL,
    acknowledged_hash VARCHAR(64),
    success_at BIGINT
);
CREATE TABLE IF NOT EXISTS em_websync_full (
    singleton_id INTEGER PRIMARY KEY,
    snapshot_revision BIGINT NOT NULL,
    pending_body LONGBLOB NOT NULL,
    pending_event_id VARCHAR(128) NOT NULL,
    included_state LONGBLOB NOT NULL,
    pending_since BIGINT NOT NULL,
    retry_at BIGINT,
    attempt_count INTEGER NOT NULL,
    success_at BIGINT
);
