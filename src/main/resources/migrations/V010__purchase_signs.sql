-- REQ-250..253 — physical purchase signs bound to a stall.
--
-- Identified by world+coordinates so two signs can never occupy the
-- same block. Sign destruction (REQ-253) deletes by the same key.
-- ON DELETE CASCADE keeps signs in lockstep with their stall: if the
-- stall is ever removed from the stalls table, the bindings vanish too.
--
-- Price is set on sign placement (third line of the placed sign) and
-- persists with the binding. Right-clicking an UNOWNED stall's sign
-- withdraws this amount from the clicker and awards the stall.

CREATE TABLE IF NOT EXISTS purchase_signs (
    world      TEXT NOT NULL,
    x          INTEGER NOT NULL,
    y          INTEGER NOT NULL,
    z          INTEGER NOT NULL,
    stall_id   TEXT NOT NULL,
    price      INTEGER NOT NULL CHECK (price > 0),
    PRIMARY KEY (world, x, y, z),
    FOREIGN KEY (stall_id) REFERENCES stalls(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_purchase_signs_stall ON purchase_signs(stall_id);
