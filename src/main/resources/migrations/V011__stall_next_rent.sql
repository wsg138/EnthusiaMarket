-- REQ-250 extension — track when the next rent collection is due per
-- stall so purchase signs can show a countdown for OWNED stalls and
-- the rent-extension flow can push the timer out by a period at a
-- time. Nullable: pre-V011 rows fall back to (owner_since + interval).
ALTER TABLE stalls ADD COLUMN next_rent_at INTEGER;
