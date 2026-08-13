package net.enthusia.market.api.moderation;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record MarketOperationRecord(
        UUID operationId,
        UUID targetId,
        String caseId,
        String stallId,
        State state,
        String snapshotChecksum,
        Optional<String> currentChecksum,
        Optional<UUID> reviewerId,
        Instant reviewDueAt,
        Instant recoveryUntil,
        long revision,
        String detail,
        Instant updatedAt
) {
    public MarketOperationRecord {
        operationId = Objects.requireNonNull(operationId, "operationId");
        targetId = Objects.requireNonNull(targetId, "targetId");
        MarketApiValidation.identifier(caseId, "case id", 64);
        MarketApiValidation.identifier(stallId, "stall id", 128);
        state = Objects.requireNonNull(state, "state");
        snapshotChecksum = MarketApiValidation.checksum(snapshotChecksum, "snapshot checksum");
        currentChecksum = Objects.requireNonNull(currentChecksum, "currentChecksum");
        currentChecksum = currentChecksum.map(value ->
                MarketApiValidation.checksum(value, "current checksum")
        );
        reviewerId = Objects.requireNonNull(reviewerId, "reviewerId");
        reviewDueAt = Objects.requireNonNull(reviewDueAt, "reviewDueAt");
        recoveryUntil = Objects.requireNonNull(recoveryUntil, "recoveryUntil");
        if (revision < 1L) {
            throw new IllegalArgumentException("operation revision must be positive");
        }
        detail = MarketApiValidation.text(detail, "detail", 512);
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
    }

    public enum State {
        PREPARED,
        MODERATION_HOLD,
        RESTORED,
        RELEASED,
        QUARANTINED
    }
}
