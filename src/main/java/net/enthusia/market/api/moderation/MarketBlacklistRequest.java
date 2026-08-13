package net.enthusia.market.api.moderation;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record MarketBlacklistRequest(
        UUID operationId,
        UUID targetId,
        String caseId,
        Optional<Instant> expiresAt
) {
    public MarketBlacklistRequest {
        operationId = Objects.requireNonNull(operationId, "operationId");
        targetId = Objects.requireNonNull(targetId, "targetId");
        MarketApiValidation.identifier(caseId, "case id", 64);
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
    }
}
