-- REQ-200, REQ-201 — per-stall co-owner roster + configurable cap.
--
-- Members are stored as a comma-separated list of UUID strings in a TEXT
-- column rather than a join table. Stalls touch this set only on owner-
-- driven mutations (add / remove member commands), never in hot paths, so
-- the simpler representation is the right trade. -1 on max_members
-- preserves the "unlimited" default for every existing stall row.

ALTER TABLE stalls ADD COLUMN members TEXT NOT NULL DEFAULT '';
ALTER TABLE stalls ADD COLUMN max_members INTEGER NOT NULL DEFAULT -1;
