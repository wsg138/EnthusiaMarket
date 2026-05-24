package net.badgersmc.em.application

import net.badgersmc.em.config.EnthusiaMarketConfig
import net.badgersmc.em.domain.auction.Auction
import net.badgersmc.em.domain.auction.AuctionId
import net.badgersmc.em.domain.auction.AuctionRepository
import net.badgersmc.em.domain.auction.AuctionState
import net.badgersmc.em.domain.auction.Bid
import net.badgersmc.em.domain.ports.EconomyProvider
import net.badgersmc.em.domain.stall.OwnerRef
import net.badgersmc.em.domain.stall.StallId
import net.badgersmc.em.domain.stall.StallRepository
import net.badgersmc.nexus.annotations.Service
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Result of an auction lifecycle operation.
 */
sealed class AuctionResult {
    /** Operation completed successfully. */
    data class Success(val auction: Auction) : AuctionResult()
    /** Operation failed with a descriptive reason. */
    data class Failure(val reason: String) : AuctionResult()
    /** Referenced auction was not found. */
    data object NotFound : AuctionResult()
}

/**
 * Report from settling expired auctions.
 */
data class SettlementReport(
    val settled: Int,
    val errors: Int
)

/**
 * Application-layer service managing the full auction lifecycle (REQ-007).
 *
 * Handles creation, bidding, cancellation, and settlement of expired auctions.
 */
@Service
class AuctionLifecycleService(
    private val auctionRepository: AuctionRepository,
    private val stallRepository: StallRepository,
    private val economy: EconomyProvider,
    private val config: EnthusiaMarketConfig
) {

    /**
     * Create a new auction for a stall.
     *
     * @param stallId the stall to auction
     * @param playerUuid the player creating the auction (must be stall owner)
     * @param startingBid the minimum bid amount
     * @param durationStr optional ISO-8601 duration string (e.g. "PT24H"), null for default
     * @return [AuctionResult.Success] with the created auction, or [AuctionResult.Failure]
     */
    fun createAuction(
        stallId: StallId,
        playerUuid: UUID,
        startingBid: Long,
        durationStr: String?
    ): AuctionResult {
        // Validate stall exists
        val stall = stallRepository.findById(stallId)
            ?: return AuctionResult.Failure("Stall not found: ${stallId.value}")

        // Validate player is stall owner
        if (stall.owner != OwnerRef.solo(playerUuid)) {
            return AuctionResult.Failure("You are not the owner of this stall")
        }

        // Validate no open auction already exists for this stall
        val existing = auctionRepository.findOpenByStall(stallId)
        if (existing != null) {
            return AuctionResult.Failure("An open auction already exists for this stall")
        }

        // Validate starting bid
        if (startingBid < config.auction.minStartingBid) {
            return AuctionResult.Failure(
                "Starting bid must be at least ${config.auction.minStartingBid}"
            )
        }

        // Parse and validate duration
        val minDuration = Duration.parse(config.auction.minDuration)
        val maxDuration = Duration.parse(config.auction.maxDuration)
        val duration = if (durationStr != null) {
            try {
                Duration.parse(durationStr)
            } catch (e: Exception) {
                return AuctionResult.Failure("Invalid duration format: $durationStr")
            }
        } else {
            Duration.parse(config.auction.defaultDuration)
        }

        if (duration < minDuration) {
            return AuctionResult.Failure(
                "Duration must be at least ${config.auction.minDuration}"
            )
        }
        if (duration > maxDuration) {
            return AuctionResult.Failure(
                "Duration must be at most ${config.auction.maxDuration}"
            )
        }

        val now = Instant.now()
        val auction = Auction(
            id = AuctionId(UUID.randomUUID().toString()),
            stallId = stallId,
            state = AuctionState.OPEN,
            startAt = now,
            endAt = now.plus(duration),
            startingBid = startingBid,
            highBid = null,
            antiSnipeWindow = Duration.ofSeconds(config.auction.antiSnipeSec.toLong())
        )

        auctionRepository.create(auction)
        return AuctionResult.Success(auction)
    }

    /**
     * Place a bid on an open auction.
     *
     * @param auctionId the auction to bid on
     * @param playerUuid the bidder
     * @param amount the bid amount
     * @return [AuctionResult.Success] with the updated auction, [AuctionResult.Failure],
     *         or [AuctionResult.NotFound]
     */
    fun placeBid(auctionId: AuctionId, playerUuid: UUID, amount: Long): AuctionResult {
        val auction = auctionRepository.findById(auctionId)
            ?: return AuctionResult.NotFound

        if (auction.state != AuctionState.OPEN) {
            return AuctionResult.Failure("Auction is not open")
        }

        return try {
            val updated = auction.placeBid(playerUuid, amount, Instant.now())
            auctionRepository.save(updated)
            AuctionResult.Success(updated)
        } catch (e: IllegalArgumentException) {
            AuctionResult.Failure(e.message ?: "Bid rejected")
        } catch (e: IllegalStateException) {
            AuctionResult.Failure(e.message ?: "Bid rejected")
        }
    }

    /**
     * Cancel an open auction. Only the stall owner may cancel.
     *
     * @param auctionId the auction to cancel
     * @param playerUuid the player requesting cancellation
     * @return [AuctionResult.Success] with the closed auction, or [AuctionResult.Failure]/[AuctionResult.NotFound]
     */
    fun cancelAuction(auctionId: AuctionId, playerUuid: UUID): AuctionResult {
        val auction = auctionRepository.findById(auctionId)
            ?: return AuctionResult.NotFound

        val stall = stallRepository.findById(auction.stallId)
            ?: return AuctionResult.Failure("Stall not found for auction")

        if (stall.owner != OwnerRef.solo(playerUuid)) {
            return AuctionResult.Failure("Only the stall owner can cancel this auction")
        }

        val closed = auction.close()
        auctionRepository.save(closed)
        return AuctionResult.Success(closed)
    }

    /**
     * Settle all expired auctions.
     *
     * For each expired auction:
     * - If there is a high bid, award the stall to the winner, pay the seller (minus fee),
     *   and mark the auction as closed.
     * - If there are no bids, just close the auction.
     *
     * @return [SettlementReport] with counts of settled and errored auctions
     */
    fun settleExpired(): SettlementReport {
        val expired = auctionRepository.findExpired()
        var settled = 0
        var errors = 0

        for (auction in expired) {
            try {
                if (auction.highBid != null) {
                    settleWithWinner(auction)
                } else {
                    // No bids — just close
                    auctionRepository.save(auction.close())
                }
                settled++
            } catch (e: Exception) {
                errors++
            }
        }

        return SettlementReport(settled = settled, errors = errors)
    }

    private fun settleWithWinner(auction: Auction) {
        val bid = auction.highBid ?: return
        val stall = stallRepository.findById(auction.stallId)
            ?: throw IllegalStateException("Stall not found for auction ${auction.id}")

        // 1. Pay seller first (so if it fails, nothing is committed)
        val feePct = config.auction.feePct
        val feeAmount = (bid.amount * feePct).toLong()
        val sellerProceeds = bid.amount - feeAmount
        val sellerUuid = extractOwnerUuid(stall)
        if (sellerUuid == null || !economy.deposit(sellerUuid, sellerProceeds)) {
            throw IllegalStateException("Failed to deposit seller proceeds for auction ${auction.id}")
        }

        // 2. Award stall to winner
        val awardAt = Instant.now()
        val updatedStall = stall.awardTo(OwnerRef.solo(bid.bidder), bid.amount, awardAt)
        stallRepository.save(updatedStall)

        // 3. Close the auction
        auctionRepository.save(auction.close())
    }

    /**
     * Extract the player UUID from the stall's owner ref.
     * Returns null for non-SOLO owner types (guild owners cannot auction).
     */
    private fun extractOwnerUuid(stall: net.badgersmc.em.domain.stall.Stall): UUID? {
        return if (stall.owner.type == net.badgersmc.em.domain.stall.OwnerType.SOLO) {
            try {
                UUID.fromString(stall.owner.id)
            } catch (_: IllegalArgumentException) {
                null
            }
        } else {
            null
        }
    }
}