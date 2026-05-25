package net.badgersmc.em.application

import net.badgersmc.em.config.EnthusiaMarketConfig
import net.badgersmc.em.domain.ports.EconomyProvider
import net.badgersmc.em.domain.ports.ItemProvider
import net.badgersmc.em.domain.shop.ShopSign
import net.badgersmc.em.domain.shop.SignDirection
import net.badgersmc.em.domain.shop.SignRepository
import net.badgersmc.em.domain.stall.OwnerType
import net.badgersmc.em.domain.stall.StallRepository
import net.badgersmc.nexus.annotations.Service
import java.util.UUID
import java.util.logging.Logger

/**
 * Application-layer service for atomic shop sign buy/sell transactions (REQ-006).
 *
 * Each [execute] call validates the sign, checks balances/inventory, performs the
 * economy and item transfers atomically, and rolls back on failure (REQ-040).
 */
@Service
class ShopTradeService(
    private val signRepository: SignRepository,
    private val stallRepository: StallRepository,
    private val economy: EconomyProvider,
    private val items: ItemProvider,
    private val config: EnthusiaMarketConfig
) {
    private val logger = Logger.getLogger(ShopTradeService::class.java.name)

    /**
     * Result of a shop trade execution.
     */
    sealed class TradeResult {
        /** Trade completed successfully. */
        data class Success(val description: String) : TradeResult()
        /** Trade failed before any irreversible step. */
        data class Failure(val reason: String) : TradeResult()
        /** Trade failed mid-flight and was rolled back. */
        data class RolledBack(val originalError: String) : TradeResult()
        /** Trade failed mid-flight AND compensation rollback also failed — state may be inconsistent. */
        data class CompensationFailed(val originalError: String, val compensationError: String) : TradeResult()
    }

    companion object {
        /** Default item amount when the sign entity does not specify one. */
        private const val DEFAULT_AMOUNT = 1
    }

    /**
     * Execute a shop trade for the given [signId] and [playerUuid].
     *
     * @return [TradeResult.Success] on success, [TradeResult.Failure] on validation
     *         failure, or [TradeResult.RolledBack] if an operation failed after
     *         irreversible steps were begun.
     */
    fun execute(signId: Long, playerUuid: UUID): TradeResult {
        // 1. Validate sign exists
        val sign = signRepository.findById(signId)
            ?: return TradeResult.Failure("Sign not found: $signId")

        // 2. Validate stall exists
        val stall = stallRepository.findById(sign.stallId)
            ?: return TradeResult.Failure("Stall not found for sign: ${sign.stallId}")

        // 3. Validate no self-trade
        if (stall.owner.type == OwnerType.SOLO && stall.owner.id == playerUuid.toString()) {
            return TradeResult.Failure("Cannot trade with your own shop sign (no self-trade)")
        }

        // 4. Resolve owner UUID — handle malformed input gracefully
        val ownerUuid = resolveOwnerUuid(stall) ?: return TradeResult.Failure("Malformed owner UUID on stall ${stall.id.value}")

        val itemKey = sign.itemKey
        val price = sign.price
        val amount = DEFAULT_AMOUNT

        // 5. Validate price is positive
        if (price <= 0) {
            return TradeResult.Failure("Invalid price: $price — must be positive")
        }

        val taxPct = config.shop.taxPct

        // 6. Validate tax percentage range
        if (taxPct < 0.0 || taxPct > 1.0) {
            return TradeResult.Failure("Invalid tax percentage: $taxPct — must be between 0.0 and 1.0")
        }

        val taxAmount = (price * taxPct).toLong()
        val sellerProceeds = price - taxAmount

        return when (sign.direction) {
            SignDirection.BUY -> executeBuy(sign, playerUuid, ownerUuid, itemKey, amount, price, sellerProceeds)
            SignDirection.SELL -> executeSell(sign, playerUuid, ownerUuid, itemKey, amount, price, sellerProceeds)
        }
    }

    private fun resolveOwnerUuid(stall: net.badgersmc.em.domain.stall.Stall): UUID? {
        return when (stall.owner.type) {
            OwnerType.SOLO -> try {
                UUID.fromString(stall.owner.id)
            } catch (_: IllegalArgumentException) {
                null
            }
            OwnerType.GUILD -> null // guild trades use player as proxy, resolved at call site
            OwnerType.NONE -> null
        }
    }

    /**
     * BUY: player sells item to the stall owner.
     *
     * Steps:
     *   1. Check player has the item
     *   2. Check owner has sufficient balance
     *   3. Take item from player
     *   4. Withdraw price from owner
     *   5. Deposit seller proceeds to player (price minus tax)
     *   6. On failure: rollback completed steps
     */
    private fun executeBuy(
        sign: ShopSign,
        playerUuid: UUID,
        ownerUuid: UUID,
        itemKey: String,
        amount: Int,
        price: Long,
        sellerProceeds: Long
    ): TradeResult {
        // Check player has the item
        if (!items.playerHasItem(playerUuid, itemKey, amount)) {
            return TradeResult.Failure("You do not have $amount x $itemKey")
        }

        // Check owner has sufficient balance
        if (economy.balance(ownerUuid) < price) {
            return TradeResult.Failure("Shop owner does not have sufficient balance")
        }

        // Step 1: Take item from player
        if (!items.takeItemFromPlayer(playerUuid, itemKey, amount)) {
            return TradeResult.Failure("Failed to take item from your inventory")
        }

        // Step 2: Withdraw price from owner
        if (!economy.withdraw(ownerUuid, price)) {
            // ROLLBACK: give item back to player
            if (!items.giveItemToPlayer(playerUuid, itemKey, amount)) {
                logger.warning("Trade rollback compensation issue: Economy withdrawal from shop owner failed | Failed to return item to player during rollback")
                return TradeResult.CompensationFailed(
                    originalError = "Economy withdrawal from shop owner failed",
                    compensationError = "Failed to return item to player during rollback"
                )
            }
            return TradeResult.RolledBack("Economy withdrawal from shop owner failed")
        }

        // Step 3: Deposit proceeds to player
        if (!economy.deposit(playerUuid, sellerProceeds)) {
            // ROLLBACK: refund owner + give item back to player
            val ownerRefunded = economy.deposit(ownerUuid, price)
            val itemReturned = items.giveItemToPlayer(playerUuid, itemKey, amount)
            if (!ownerRefunded || !itemReturned) {
                val failures = mutableListOf<String>()
                if (!ownerRefunded) failures.add("failed to refund owner")
                if (!itemReturned) failures.add("failed to return item")
                logger.warning("Trade rollback compensation issue: Economy deposit to player failed | ${failures.joinToString("; ")}")
                return TradeResult.CompensationFailed(
                    originalError = "Economy deposit to player failed",
                    compensationError = failures.joinToString("; ")
                )
            }
            return TradeResult.RolledBack("Economy deposit to player failed")
        }

        return TradeResult.Success(
            "Sold $amount x $itemKey for $price (${sellerProceeds} after tax)"
        )
    }

    /**
     * SELL: player buys item from the stall owner.
     *
     * Steps:
     *   1. Check player has sufficient balance
     *   2. Withdraw price from player
     *   3. Deposit seller proceeds to owner
     *   4. Give item to player
     *   5. On failure: rollback completed steps
     */
    private fun executeSell(
        sign: ShopSign,
        playerUuid: UUID,
        ownerUuid: UUID,
        itemKey: String,
        amount: Int,
        price: Long,
        sellerProceeds: Long
    ): TradeResult {
        // Check player has sufficient balance
        if (economy.balance(playerUuid) < price) {
            return TradeResult.Failure("You do not have sufficient balance")
        }

        // Step 1: Withdraw price from player
        if (!economy.withdraw(playerUuid, price)) {
            return TradeResult.Failure("Failed to withdraw balance")
        }

        // Step 2: Deposit proceeds to owner
        if (!economy.deposit(ownerUuid, sellerProceeds)) {
            // ROLLBACK: refund player
            if (!economy.deposit(playerUuid, price)) {
                logger.warning("Trade rollback compensation issue: Failed to credit shop owner | Failed to refund player during rollback")
                return TradeResult.CompensationFailed(
                    originalError = "Failed to credit shop owner",
                    compensationError = "Failed to refund player during rollback"
                )
            }
            return TradeResult.RolledBack("Failed to credit shop owner")
        }

        // Step 3: Give item to player
        if (!items.giveItemToPlayer(playerUuid, itemKey, amount)) {
            // ROLLBACK: refund player + take money back from owner
            val playerRefunded = economy.deposit(playerUuid, price)
            val ownerDebited = economy.withdraw(ownerUuid, sellerProceeds)
            if (!playerRefunded || !ownerDebited) {
                val failures = mutableListOf<String>()
                if (!playerRefunded) failures.add("failed to refund player")
                if (!ownerDebited) failures.add("failed to debit owner")
                logger.warning("Trade rollback compensation issue: Failed to give item to player | ${failures.joinToString("; ")}")
                return TradeResult.CompensationFailed(
                    originalError = "Failed to give item to player",
                    compensationError = failures.joinToString("; ")
                )
            }
            return TradeResult.RolledBack("Failed to give item to player")
        }

        return TradeResult.Success(
            "Purchased $amount x $itemKey for $price"
        )
    }
}