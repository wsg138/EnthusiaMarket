package net.enthusia.market.api.moderation;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record MarketConfiscationApproval(
        UUID operationId,
        UUID reviewerId,
        String expectedSnapshotChecksum,
        Instant reviewedAt
) {
    public MarketConfiscationApproval {
        operationId = Objects.requireNonNull(operationId, "operationId");
        reviewerId = Objects.requireNonNull(reviewerId, "reviewerId");
        expectedSnapshotChecksum = MarketApiValidation.checksum(
                expectedSnapshotChecksum,
                "expected snapshot checksum"
        );
        reviewedAt = Objects.requireNonNull(reviewedAt, "reviewedAt");
    }
}
