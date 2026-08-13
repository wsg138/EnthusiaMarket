package net.enthusia.market.api.moderation;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record MarketStallRecord(
        String id,
        String world,
        String state,
        MarketOwnership ownership,
        long revision,
        boolean moderationLocked,
        Optional<Instant> reviewDueAt
) {
    public MarketStallRecord {
        MarketApiValidation.identifier(id, "stall id", 128);
        MarketApiValidation.identifier(world, "world", 128);
        MarketApiValidation.identifier(state, "stall state", 48);
        ownership = Objects.requireNonNull(ownership, "ownership");
        if (revision < 0L) {
            throw new IllegalArgumentException("stall revision cannot be negative");
        }
        reviewDueAt = Objects.requireNonNull(reviewDueAt, "reviewDueAt");
    }
}
