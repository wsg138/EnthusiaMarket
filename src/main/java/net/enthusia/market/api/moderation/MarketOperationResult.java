package net.enthusia.market.api.moderation;

import java.util.Objects;
import java.util.Optional;

public record MarketOperationResult(
        Status status,
        Optional<MarketOperationRecord> operation,
        String detail
) {
    public MarketOperationResult {
        status = Objects.requireNonNull(status, "status");
        operation = Objects.requireNonNull(operation, "operation");
        detail = MarketApiValidation.text(detail, "detail", 512);
    }

    public enum Status {
        PREPARED,
        REPLAYED,
        HELD,
        RESTORED,
        RELEASED,
        REJECTED,
        CONFLICT,
        QUARANTINED
    }
}
