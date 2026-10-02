package net.badgersmc.em.infrastructure.moderation

import net.enthusia.market.api.moderation.MarketBlacklistResult
import net.enthusia.market.api.moderation.StallBlacklistState
import java.sql.Connection
import java.sql.SQLException
import java.util.Optional
import javax.sql.DataSource

internal fun marketBlacklistResult(
    status: MarketBlacklistResult.Status,
    blacklist: StallBlacklistState?,
    detail: String,
): MarketBlacklistResult = MarketBlacklistResult(
    status,
    Optional.ofNullable(blacklist),
    detail,
)

internal inline fun DataSource.marketBlacklistTransaction(
    block: (Connection) -> MarketBlacklistResult,
): MarketBlacklistResult = try {
    inTransaction(block)
} catch (conflict: MarketModerationConflict) {
    marketBlacklistResult(
        MarketBlacklistResult.Status.CONFLICT,
        null,
        conflict.message ?: "Blacklist conflict",
    )
} catch (failure: SQLException) {
    if (!failure.isTransactionContention()) throw failure
    marketBlacklistResult(
        MarketBlacklistResult.Status.CONFLICT,
        null,
        "Market blacklist changed concurrently",
    )
}
