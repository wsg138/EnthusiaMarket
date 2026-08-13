package net.enthusia.market.api.moderation;

import java.util.Objects;
import java.util.UUID;

public record MarketRestoreRequest(
        UUID operationId,
        UUID reviewerId,
        String expectedCurrentChecksum
) {
    public MarketRestoreRequest {
        operationId = Objects.requireNonNull(operationId, "operationId");
        reviewerId = Objects.requireNonNull(reviewerId, "reviewerId");
        expectedCurrentChecksum = MarketApiValidation.checksum(
                expectedCurrentChecksum,
                "expected current checksum"
        );
    }
}
