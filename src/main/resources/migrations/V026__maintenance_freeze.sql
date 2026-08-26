-- V026 — maintenance freeze state.
--
-- Single-row meta table recording an active maintenance freeze. While frozen,
-- the rent scheduler and auction scheduler pause all timer-driven processing
-- (grace/eviction transitions, auction settlement, reminders). On unfreeze,
-- every timer column is shifted forward by the frozen wall-clock duration so
-- players lose no time while the server is closed for maintenance.
--
-- State survives restarts on purpose: the server is typically TAKEN DOWN for
-- the maintenance window, and the freeze must still be active when it comes
-- back up until an admin runs `/em maintenance unfreeze`.
--
-- frozen     = 1 while a freeze is active (0 otherwise)
-- started_at = epoch millis when the freeze began (null when not frozen)

CREATE TABLE IF NOT EXISTS maintenance_freeze (
    id          INTEGER PRIMARY KEY,
    frozen      INTEGER NOT NULL DEFAULT 0,
    started_at  BIGINT
);

INSERT INTO maintenance_freeze (id, frozen) VALUES (1, 0);
