package net.enthusia.market.api.moderation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/** Stable cross-plugin boundary for EnthusiaMarket moderation operations. */
public interface MarketModerationApi {
    int API_VERSION = 1;

    int apiVersion();

    CompletionStage<List<MarketStallRecord>> findStalls(UUID playerId);

    CompletionStage<Optional<StallBlacklistState>> getStallBlacklist(UUID playerId);

    CompletionStage<Boolean> canAcquire(UUID playerId);

    CompletionStage<MarketOperationResult> prepare(MarketOperationRequest request);

    CompletionStage<MarketOperationResult> confiscate(MarketConfiscationApproval approval);

    CompletionStage<MarketOperationResult> restore(MarketRestoreRequest request);

    CompletionStage<MarketOperationResult> release(
            UUID operationId,
            String expectedSnapshotChecksum
    );

    CompletionStage<Optional<MarketOperationRecord>> findOperation(UUID operationId);

    CompletionStage<MarketBlacklistResult> applyBlacklist(MarketBlacklistRequest request);

    CompletionStage<MarketBlacklistResult> removeBlacklist(MarketBlacklistRemoval removal);
}
