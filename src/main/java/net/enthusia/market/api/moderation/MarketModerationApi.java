package net.enthusia.market.api.moderation;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/** Stable cross-plugin boundary for EnthusiaMarket moderation operations. */
public interface MarketModerationApi {
    /** Current public API contract version. */
    int API_VERSION = 1;

    /** Returns the implemented moderation API version. */
    int apiVersion();

    /** Finds market stalls associated with the supplied player. */
    CompletionStage<List<MarketStallRecord>> findStalls(UUID playerId);

    /** Returns the player's current stall blacklist state when one exists. */
    CompletionStage<Optional<StallBlacklistState>> getStallBlacklist(UUID playerId);

    /**
     * Returns an asynchronous acquisition decision; the boolean-style name is
     * retained because it is part of the published cross-plugin contract.
     */
    @SuppressWarnings("PMD.LinguisticNaming")
    CompletionStage<Boolean> canAcquire(UUID playerId);

    /** Prepares a durable moderation operation and snapshot. */
    CompletionStage<MarketOperationResult> prepare(MarketOperationRequest request);

    /** Confiscates the prepared stall after explicit approval. */
    CompletionStage<MarketOperationResult> confiscate(MarketConfiscationApproval approval);

    /** Restores a confiscated stall from its approved snapshot. */
    CompletionStage<MarketOperationResult> restore(MarketRestoreRequest request);

    /** Releases a moderation hold after verifying the expected snapshot checksum. */
    CompletionStage<MarketOperationResult> release(
            UUID operationId,
            String snapshotChecksum
    );

    /** Finds a durable moderation operation by identifier. */
    CompletionStage<Optional<MarketOperationRecord>> findOperation(UUID operationId);

    /** Applies or replays a player market blacklist request. */
    CompletionStage<MarketBlacklistResult> applyBlacklist(MarketBlacklistRequest request);

    /** Removes a player market blacklist using optimistic revision fencing. */
    CompletionStage<MarketBlacklistResult> removeBlacklist(MarketBlacklistRemoval removal);
}
