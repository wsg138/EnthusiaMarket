package net.enthusia.market.api.moderation;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record StallBlacklistState(
        UUID playerId,
        Status status,
        Optional<Instant> expiresAt,
        String caseId,
        UUID operationId,
        long revision,
        Instant updatedAt
) {
    private static final long MINIMUM_REVISION = 1L;

    public StallBlacklistState {
        playerId = Objects.requireNonNull(playerId, "playerId");
        status = Objects.requireNonNull(status, "status");
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        MarketApiValidation.identifier(caseId, "case id", 64);
        operationId = Objects.requireNonNull(operationId, "operationId");
        if (revision < MINIMUM_REVISION) {
            throw new IllegalArgumentException("blacklist revision must be positive");
        }
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public boolean activeAt(Instant now) {
        Objects.requireNonNull(now, "now");
        return status == Status.ACTIVE && expiresAt.map(value -> now.isBefore(value)).orElse(true);
    }

    public enum Status {
        ACTIVE,
        REMOVED
    }
}
