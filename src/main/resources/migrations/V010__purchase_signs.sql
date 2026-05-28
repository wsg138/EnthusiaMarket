-- REQ-250..253 — physical purchase signs bound to a stall.
--
-- Identified by world+coordinates so two signs can never occupy the
-- same block. Sign destruction (REQ-253) deletes by the same key.
-- ON DELETE CASCADE keeps signs in lockstep with their stall: if the
-- stall is ever removed from the stalls table, the bindings vanish too.

CREATE TABLE IF NOT EXISTS purchase_signs (
    world      TEXT NOT NULL,
    x          INTEGER NOT NULL,
    y          INTEGER NOT NULL,
    z          INTEGER NOT NULL,
    stall_id   TEXT NOT NULL,
    kind       TEXT NOT NULL,
    PRIMARY KEY (world, x, y, z),
    FOREIGN KEY (stall_id) REFERENCES stalls(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_purchase_signs_stall ON purchase_signs(stall_id);
