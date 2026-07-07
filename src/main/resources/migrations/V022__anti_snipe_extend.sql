-- V022__anti_snipe_extend.sql
-- Splits anti-snipe into two configurable values:
--   anti_snipe_sec         = trigger window (bid within this many seconds of endAt)
--   anti_snipe_extend_sec  = extension amount (how much to add when triggered)
-- Previously both were the same value (anti_snipe_sec). Backfill ensures
-- existing auctions preserve their exact old behaviour (extension == window).

ALTER TABLE auctions ADD COLUMN anti_snipe_extend_sec INTEGER NOT NULL DEFAULT 30;

-- Preserve old behaviour for in-flight rows: extension == window,
-- regardless of what anti_snipe_sec was previously configured to.
UPDATE auctions SET anti_snipe_extend_sec = anti_snipe_sec;
