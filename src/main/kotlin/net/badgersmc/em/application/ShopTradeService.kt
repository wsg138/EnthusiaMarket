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

        // Determine the player's and owner's UUIDs
        val ownerUuid = when (stall.owner.type) {
            OwnerType.SOLO -> UUID.fromString(stall.owner.id)
            OwnerType.GUILD -> playerUuid  // guild trades use player as proxy
            OwnerType.NONE -> return TradeResult.Failure("Stall has no owner")
        }

        val itemKey = sign.itemKey
        val price = sign.price
        val amount = DEFAULT_AMOUNT
        val taxPct = config.shop.taxPct
        val taxAmount = (price * taxPct).toLong()
        val sellerProceeds = price - taxAmount

        return when (sign.direction) {
            SignDirection.BUY -> executeBuy(sign, playerUuid, ownerUuid, itemKey, amount, price, sellerProceeds)
            SignDirection.SELL -> executeSell(sign, playerUuid, ownerUuid, itemKey, amount, price, sellerProceeds)
        }
    }

    /**
     * BUY: player sells item to the stall owner.
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
            items.giveItemToPlayer(playerUuid, itemKey, amount)
            return TradeResult.RolledBack("Economy withdrawal from shop owner failed")
        }

        // Step 3: Deposit proceeds to player
        if (!economy.deposit(playerUuid, sellerProceeds)) {
            // ROLLBACK: refund owner + give item back to player
            economy.deposit(ownerUuid, price)
            items.giveItemToPlayer(playerUuid, itemKey, amount)
            return TradeResult.RolledBack("Economy deposit to player failed")
        }

        return TradeResult.Success(
            "Sold $amount x $itemKey for $price (${sellerProceeds} after tax)"
        )
    }

    /**
     * SELL: player buys item from the stall owner.
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
            economy.deposit(playerUuid, price)
            return TradeResult.RolledBack("Failed to credit shop owner")
        }

        // Step 3: Give item to player
        if (!items.giveItemToPlayer(playerUuid, itemKey, amount)) {
            // ROLLBACK: refund player + take money back from owner
            economy.deposit(playerUuid, price)
            economy.withdraw(ownerUuid, sellerProceeds)
            return TradeResult.RolledBack("Failed to give item to player")
        }

        return TradeResult.Success(
            "Purchased $amount x $itemKey for $price"
        )
    }
}