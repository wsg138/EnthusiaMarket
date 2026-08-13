package net.enthusia.market.api.moderation;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record MarketOperationRequest(
        UUID operationId,
        UUID targetId,
        String caseId,
        String stallId,
        Instant reviewDueAt,
        Instant recoveryUntil,
        Optional<Instant> blacklistExpiresAt
) {
    private static final Duration MAXIMUM_RECOVERY = Duration.ofDays(31);

    public MarketOperationRequest {
        operationId = Objects.requireNonNull(operationId, "operationId");
        targetId = Objects.requireNonNull(targetId, "targetId");
        MarketApiValidation.identifier(caseId, "case id", 64);
        MarketApiValidation.identifier(stallId, "stall id", 128);
        reviewDueAt = Objects.requireNonNull(reviewDueAt, "reviewDueAt");
        recoveryUntil = Objects.requireNonNull(recoveryUntil, "recoveryUntil");
        blacklistExpiresAt = Objects.requireNonNull(blacklistExpiresAt, "blacklistExpiresAt");
        if (!recoveryUntil.isAfter(reviewDueAt)
                || Duration.between(reviewDueAt, recoveryUntil).compareTo(MAXIMUM_RECOVERY) > 0) {
            throw new IllegalArgumentException("recovery window must be positive and at most 31 days");
        }
    }
}
