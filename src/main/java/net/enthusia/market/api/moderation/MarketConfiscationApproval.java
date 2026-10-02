package net.enthusia.market.api.moderation;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record MarketConfiscationApproval(
        UUID operationId,
        UUID reviewerId,
        String snapshotChecksum,
        Instant reviewedAt
) {
    public MarketConfiscationApproval {
        operationId = Objects.requireNonNull(operationId, "operationId");
        reviewerId = Objects.requireNonNull(reviewerId, "reviewerId");
        snapshotChecksum = MarketApiValidation.checksum(
                snapshotChecksum,
                "expected snapshot checksum"
        );
        reviewedAt = Objects.requireNonNull(reviewedAt, "reviewedAt");
    }

    /** Compatibility accessor preserving the original moderation contract name. */
    public String expectedSnapshotChecksum() {
        return snapshotChecksum;
    }
}
