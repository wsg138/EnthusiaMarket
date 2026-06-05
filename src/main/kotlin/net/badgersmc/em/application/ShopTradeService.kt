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
        /** Trade failed mid-flight AND compensation rollback also failed. */
        data class CompensationFailed(val originalError: String, val compensationError: String) : TradeResult()
    }

    companion object {
        /** Default item amount when the sign entity does not specify one. */
        private const val DEFAULT_AMOUNT = 1
    }

    /**
     * Execute a shop trade for the given [signId] and [playerUuid].
     */
    fun execute(signId: Long, playerUuid: UUID): TradeResult {
        val sign = signRepository.findById(signId)
            ?: return TradeResult.Failure("Sign not found: $signId")

        val stall = stallRepository.findById(sign.stallId)
            ?: return TradeResult.Failure("Stall not found for sign: ${sign.stallId}")

        // Self-trade guard (REQ-006 / C8): block both SOLO owners trading
        // at their own shop and guild members trading at a shop owned by
        // their own guild. resolveOwnerUuid returns playerUuid for
        // OwnerType.GUILD (treating the actor as the effective owner for
        // economy transfer), so ownerUuid == playerUuid catches both cases.
        val ownerUuid = resolveOwnerUuid(stall, playerUuid)
            ?: return TradeResult.Failure("Invalid or unsupported stall owner on ${stall.id.value}")

        if (ownerUuid == playerUuid) {
            return TradeResult.Failure("Cannot trade with your own shop sign (no self-trade)")
        }

        val validation = validateTradeParams(sign)
        if (validation != null) return validation

        val taxAmount = (sign.price * config.shop.taxPct).toLong()
        val sellerProceeds = sign.price - taxAmount

        return when (sign.direction) {
            SignDirection.BUY -> executeBuy(sign, playerUuid, ownerUuid, sign.itemKey, DEFAULT_AMOUNT, sign.price, taxAmount, sellerProceeds)
            SignDirection.SELL -> executeSell(sign, playerUuid, ownerUuid, sign.itemKey, DEFAULT_AMOUNT, sign.price, taxAmount, sellerProceeds)
            SignDirection.TRADE -> TradeResult.Failure("Barter not supported through sign trades")
        }
    }

    private fun validateTradeParams(sign: ShopSign): TradeResult.Failure? {
        if (sign.price <= 0) {
            return TradeResult.Failure("Invalid price: ${sign.price} — must be positive")
        }
        val taxPct = config.shop.taxPct
        if (taxPct < 0.0 || taxPct > 1.0) {
            return TradeResult.Failure("Invalid tax percentage: $taxPct — must be between 0.0 and 1.0")
        }
        return null
    }

    private fun resolveOwnerUuid(stall: net.badgersmc.em.domain.stall.Stall, playerUuid: UUID): UUID? {
        return when (stall.owner.type) {
            OwnerType.SOLO -> try { UUID.fromString(stall.owner.id) } catch (_: IllegalArgumentException) { null }
            OwnerType.GUILD -> playerUuid
            OwnerType.NONE -> null
        }
    }

    /**
     * The tax destination is a UUID string in config; the literal
     * "system" (or any value that doesn't parse as a UUID) routes the
     * tax to a no-op sink — same compromise as SellOfferService and
     * the existing shop trade tax math.
     */
    private fun parseTaxDestination(raw: String): UUID? = try {
        UUID.fromString(raw.trim())
    } catch (_: IllegalArgumentException) {
        null
    }

    /**
     * Route the sign-shop tax to the configured destination after a
     * successful seller-side deposit. Tax destination "system" (or
     * any unparseable value) is a no-op sink; a non-zero tax with a
     * parseable UUID destination is deposited to that account.
     * Failures are logged but do not roll back the seller deposit —
     * the trade is already committed from the caller's perspective.
     */
    private fun routeTaxToDestination(taxAmount: Long) {
        if (taxAmount <= 0) return
        val taxDestination = parseTaxDestination(config.shop.taxDestination) ?: return
        if (!economy.deposit(taxDestination, taxAmount)) {
            logger.warning(
                "ShopTradeService: tax deposit failed for destination $taxDestination " +
                    "(tax=$taxAmount); seller proceeds already credited."
            )
        }
    }

    /**
     * BUY: player sells item to the stall owner.
     */
    @Suppress("LongParameterList")
    private fun executeBuy(
        @Suppress("UnusedParameter") sign: ShopSign, playerUuid: UUID, ownerUuid: UUID,
        itemKey: String, amount: Int, price: Long, taxAmount: Long, sellerProceeds: Long
    ): TradeResult {
        if (!items.playerHasItem(playerUuid, itemKey, amount)) {
            return TradeResult.Failure("You do not have $amount x $itemKey")
        }
        if (economy.balance(ownerUuid) < price) {
            return TradeResult.Failure("Shop owner does not have sufficient balance")
        }
        if (!items.takeItemFromPlayer(playerUuid, itemKey, amount)) {
            return TradeResult.Failure("Failed to take item from your inventory")
        }
        if (!economy.withdraw(ownerUuid, price)) {
            return rollbackBuyWithdraw(playerUuid, itemKey, amount, price)
        }
        if (!economy.deposit(playerUuid, sellerProceeds)) {
            return rollbackBuyDeposit(playerUuid, ownerUuid, itemKey, amount, price)
        }
        // C7 — route the sign-shop tax to the configured destination
        // (system/unparseable values are a no-op sink; failures are
        // logged but don't roll back the trade).
        routeTaxToDestination(taxAmount)
        return TradeResult.Success("Sold $amount x $itemKey for $price ($sellerProceeds after tax)")
    }

    private fun rollbackBuyWithdraw(playerUuid: UUID, itemKey: String, amount: Int, @Suppress("UnusedParameter") price: Long): TradeResult {
        if (!items.giveItemToPlayer(playerUuid, itemKey, amount)) {
            logger.warning("Trade rollback: withdraw failed, item return failed")
            return TradeResult.CompensationFailed(
                originalError = "Economy withdrawal from shop owner failed",
                compensationError = "Failed to return item to player during rollback"
            )
        }
        return TradeResult.RolledBack("Economy withdrawal from shop owner failed")
    }

    private fun rollbackBuyDeposit(
        playerUuid: UUID, ownerUuid: UUID, itemKey: String, amount: Int, price: Long
    ): TradeResult {
        val ownerRefunded = economy.deposit(ownerUuid, price)
        val itemReturned = items.giveItemToPlayer(playerUuid, itemKey, amount)
        if (!ownerRefunded || !itemReturned) {
            val failures = buildList {
                if (!ownerRefunded) add("failed to refund owner")
                if (!itemReturned) add("failed to return item")
            }
            logger.warning("Trade rollback: deposit failed, ${failures.joinToString("; ")}")
            return TradeResult.CompensationFailed(
                originalError = "Economy deposit to player failed",
                compensationError = failures.joinToString("; ")
            )
        }
        return TradeResult.RolledBack("Economy deposit to player failed")
    }

    /**
     * SELL: player buys item from the stall owner.
     */
    @Suppress("LongParameterList")
    private fun executeSell(
        @Suppress("UnusedParameter") sign: ShopSign, playerUuid: UUID, ownerUuid: UUID,
        itemKey: String, amount: Int, price: Long, taxAmount: Long, sellerProceeds: Long
    ): TradeResult {
        if (economy.balance(playerUuid) < price) {
            return TradeResult.Failure("You do not have sufficient balance")
        }
        if (!economy.withdraw(playerUuid, price)) {
            return TradeResult.Failure("Failed to withdraw balance")
        }
        if (!economy.deposit(ownerUuid, sellerProceeds)) {
            return rollbackSellDeposit(playerUuid, price)
        }
        // C7 — route the sign-shop tax to the configured destination
        // (system/unparseable values are a no-op sink; failures are
        // logged but don't roll back the trade).
        routeTaxToDestination(taxAmount)
        if (!items.giveItemToPlayer(playerUuid, itemKey, amount)) {
            return rollbackSellItem(playerUuid, ownerUuid, sellerProceeds, price)
        }
        return TradeResult.Success("Purchased $amount x $itemKey for $price")
    }

    private fun rollbackSellDeposit(playerUuid: UUID, price: Long): TradeResult {
        if (!economy.deposit(playerUuid, price)) {
            logger.warning("Trade rollback: owner deposit failed, player refund failed")
            return TradeResult.CompensationFailed(
                originalError = "Failed to credit shop owner",
                compensationError = "Failed to refund player during rollback"
            )
        }
        return TradeResult.RolledBack("Failed to credit shop owner")
    }

    private fun rollbackSellItem(
        playerUuid: UUID, ownerUuid: UUID, sellerProceeds: Long, price: Long
    ): TradeResult {
        val playerRefunded = economy.deposit(playerUuid, price)
        val ownerDebited = economy.withdraw(ownerUuid, sellerProceeds)
        if (!playerRefunded || !ownerDebited) {
            val failures = buildList {
                if (!playerRefunded) add("failed to refund player")
                if (!ownerDebited) add("failed to debit owner")
            }
            logger.warning("Trade rollback: item give failed, ${failures.joinToString("; ")}")
            return TradeResult.CompensationFailed(
                originalError = "Failed to give item to player",
                compensationError = failures.joinToString("; ")
            )
        }
        return TradeResult.RolledBack("Failed to give item to player")
    }
}
