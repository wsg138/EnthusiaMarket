package net.enthusia.market.api.moderation;

import java.util.Objects;
import java.util.Optional;

public record MarketBlacklistResult(
        Status status,
        Optional<StallBlacklistState> blacklist,
        String detail
) {
    public MarketBlacklistResult {
        status = Objects.requireNonNull(status, "status");
        blacklist = Objects.requireNonNull(blacklist, "blacklist");
        detail = MarketApiValidation.text(detail, "detail", 512);
    }

    public enum Status {
        APPLIED,
        REMOVED,
        REPLAYED,
        CONFLICT,
        REJECTED
    }
}
