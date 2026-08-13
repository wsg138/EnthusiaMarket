package net.enthusia.market.api.moderation;

import java.util.Objects;
import java.util.UUID;

public record MarketBlacklistRemoval(
        UUID operationId,
        UUID targetId,
        String caseId,
        long expectedRevision
) {
    private static final long MINIMUM_REVISION = 1L;

    public MarketBlacklistRemoval {
        operationId = Objects.requireNonNull(operationId, "operationId");
        targetId = Objects.requireNonNull(targetId, "targetId");
        MarketApiValidation.identifier(caseId, "case id", 64);
        if (expectedRevision < MINIMUM_REVISION) {
            throw new IllegalArgumentException("expected blacklist revision must be positive");
        }
    }
}
