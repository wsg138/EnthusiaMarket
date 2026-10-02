package net.enthusia.market.api.moderation;

import java.util.Objects;
import java.util.UUID;

public record MarketRestoreRequest(
        UUID operationId,
        UUID reviewerId,
        String currentChecksum
) {
    public MarketRestoreRequest {
        operationId = Objects.requireNonNull(operationId, "operationId");
        reviewerId = Objects.requireNonNull(reviewerId, "reviewerId");
        currentChecksum = MarketApiValidation.checksum(
                currentChecksum,
                "expected current checksum"
        );
    }

    /** Compatibility accessor preserving the original moderation contract name. */
    public String expectedCurrentChecksum() {
        return currentChecksum;
    }
}
