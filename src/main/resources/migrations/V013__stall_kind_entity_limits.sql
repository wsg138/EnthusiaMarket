-- REQ-220/222 — per-stall region kind + entity-limit overrides. kind keys
-- into entitylimits.yml groups; extra_entities (JSON map of type->extra) and
-- extra_total grant individual stalls additional allowance. Existing rows
-- backfill to the 'default' kind with no extras.
ALTER TABLE stalls ADD COLUMN kind TEXT NOT NULL DEFAULT 'default';
ALTER TABLE stalls ADD COLUMN extra_entities TEXT NOT NULL DEFAULT '';
ALTER TABLE stalls ADD COLUMN extra_total INTEGER NOT NULL DEFAULT 0;