package net.enthusia.market.api.moderation;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record MarketStallRecord(
        String stallId,
        String world,
        String state,
        MarketOwnership ownership,
        long revision,
        boolean moderationLocked,
        Optional<Instant> reviewDueAt
) {
    /** Smallest valid stall revision exposed through the moderation API. */
    private static final long MINIMUM_REVISION = 0L;

    public MarketStallRecord {
        MarketApiValidation.identifier(stallId, "stall id", 128);
        MarketApiValidation.identifier(world, "world", 128);
        MarketApiValidation.identifier(state, "stall state", 48);
        ownership = Objects.requireNonNull(ownership, "ownership");
        if (revision < MINIMUM_REVISION) {
            throw new IllegalArgumentException("stall revision cannot be negative");
        }
        reviewDueAt = Objects.requireNonNull(reviewDueAt, "reviewDueAt");
    }

    /** Compatibility accessor preserving the original record-style API name. */
    @SuppressWarnings("PMD.ShortMethodName")
    public String id() {
        return stallId;
    }

    /** Bean-style aliases retained for reflection-based Staff integrations. */
    public String getId() {
        return stallId;
    }

    public String getWorld() {
        return world;
    }

    public String getState() {
        return state;
    }

    public MarketOwnership getOwnership() {
        return ownership;
    }
}
