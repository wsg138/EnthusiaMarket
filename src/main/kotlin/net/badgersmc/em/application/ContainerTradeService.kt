package net.badgersmc.em.application

import net.badgersmc.em.domain.ports.EconomyProvider
import net.badgersmc.em.domain.shop.Shop
import net.badgersmc.em.domain.stall.OwnerType
import net.badgersmc.em.domain.stall.StallId
import net.badgersmc.em.domain.stall.StallRepository
import net.badgersmc.nexus.annotations.Service
import org.bukkit.Bukkit
import org.bukkit.block.Container
import org.bukkit.inventory.ItemStack
import java.util.Base64
import java.util.UUID
import java.util.logging.Logger

/**
 * Result of a container-based trade execution.
 */
sealed class ContainerTradeResult {
    /** Trade completed successfully. */
    data class Success(val message: String) : ContainerTradeResult()
    /** Trade failed before any irreversible step. */
    data class Failure(val reason: String) : ContainerTradeResult()
    /** Trade failed mid-flight and compensation also failed — state may be inconsistent. */
    data class CompensationFailed(val error: String, val compensation: String) : ContainerTradeResult()
}

/**
 * Application-layer service for container inventory trades (TDD-53).
 *
 * Executes BUY/SELL trades using a linked container's REAL inventory rather than
 * virtual items. Each trade moves physical ItemStacks between the player and the
 * container, with economy operations to transfer currency.
 */
@Service
open class ContainerTradeService(
    private val stallRepository: StallRepository,
    private val economy: EconomyProvider,
    private val logger: Logger
) {
    /**
     * BUY: player sells item to the shop (player → container, owner pays player).
     *
     * Steps:
     *   1. Validate shop is not frozen
     *   2. Validate stall exists and resolve owner UUID
     *   3. Check player is online and has the required item
     *   4. Check owner has sufficient balance
     *   5. Get container from shop coordinates
     *   6. Remove item from player → add to container → pay player
     *   7. Rollback on failure at any step
     */
    fun executeBuy(shop: Shop, playerUuid: UUID): ContainerTradeResult {
        if (shop.frozen) return ContainerTradeResult.Failure("This shop is frozen")

        val stall = stallRepository.findById(StallId(shop.stallId))
            ?: return ContainerTradeResult.Failure("Stall not found")

        val ownerUuid = resolveOwnerUuid(stall)
            ?: return ContainerTradeResult.Failure("Invalid owner")

        val player = Bukkit.getPlayer(playerUuid)
            ?: return ContainerTradeResult.Failure("Player not online")

        val sellStack = deserializeStack(shop.sellItem)?.apply { amount = shop.sellAmount }
            ?: return ContainerTradeResult.Failure("Invalid item")

        // Check player has the item
        if (!player.inventory.containsAtLeast(sellStack, shop.sellAmount))
            return ContainerTradeResult.Failure("You don't have the items to sell")

        // Check owner has enough money
        if (economy.balance(ownerUuid) < shop.costAmount.toLong())
            return ContainerTradeResult.Failure("Shop owner can't afford this")

        // Get container
        val container = getContainer(shop)
            ?: return ContainerTradeResult.Failure("Container missing")
        val containerInv = container.inventory

        // Step 1: Remove item from player
        player.inventory.removeItem(sellStack.clone())

        // Step 2: Add item to container
        val remainder = containerInv.addItem(sellStack.clone())
        if (remainder.isNotEmpty()) {
            // ROLLBACK: return item to player
            player.inventory.addItem(sellStack)
            return ContainerTradeResult.Failure("Container is full")
        }

        // Step 3: Withdraw from owner
        if (!economy.withdraw(ownerUuid, shop.costAmount.toLong())) {
            // ROLLBACK: remove item from container, return item to player
            containerInv.removeItem(sellStack)
            player.inventory.addItem(sellStack)
            return ContainerTradeResult.CompensationFailed(
                error = "Owner payment failed",
                compensation = "Item returned"
            )
        }

        // Step 4: Deposit to player
        if (!economy.deposit(playerUuid, shop.costAmount.toLong())) {
            // ROLLBACK: refund owner, remove item from container, return item to player
            economy.deposit(ownerUuid, shop.costAmount.toLong())
            containerInv.removeItem(sellStack)
            player.inventory.addItem(sellStack)
            return ContainerTradeResult.CompensationFailed(
                error = "Player deposit failed",
                compensation = "Full rollback"
            )
        }

        Bukkit.getPluginManager().callEvent(
            net.badgersmc.em.events.PostShopTransactionEvent(
                buyer = player,
                landlordId = ownerUuid,
                item = sellStack,
                quantity = shop.sellAmount,
                pricePaid = shop.costAmount.toDouble()
            )
        )

        return ContainerTradeResult.Success("Sold ${shop.sellAmount}x for ${shop.costAmount}")
    }

    /**
     * SELL: player buys item from the shop (container → player, player pays owner).
     *
     * Steps:
     *   1. Validate shop is not frozen
     *   2. Validate stall exists and resolve owner UUID
     *   3. Check player is online
     *   4. Check container has the item in stock
     *   5. Check player has sufficient balance
     *   6. Withdraw from player → deposit to owner → remove from container → give to player
     *   7. Rollback on failure at any step
     */
    fun executeSell(shop: Shop, playerUuid: UUID): ContainerTradeResult {
        if (shop.frozen) return ContainerTradeResult.Failure("This shop is frozen")

        val stall = stallRepository.findById(StallId(shop.stallId))
            ?: return ContainerTradeResult.Failure("Stall not found")

        val ownerUuid = resolveOwnerUuid(stall)
            ?: return ContainerTradeResult.Failure("Invalid owner")

        val player = Bukkit.getPlayer(playerUuid)
            ?: return ContainerTradeResult.Failure("Player not online")

        val sellStack = deserializeStack(shop.sellItem)?.apply { amount = shop.sellAmount }
            ?: return ContainerTradeResult.Failure("Invalid item")

        val container = getContainer(shop)
            ?: return ContainerTradeResult.Failure("Container missing")
        val containerInv = container.inventory

        // Check container has the item in stock
        if (!containerInv.containsAtLeast(sellStack, shop.sellAmount))
            return ContainerTradeResult.Failure("Out of stock")

        // Check player has sufficient funds
        if (economy.balance(playerUuid) < shop.costAmount.toLong())
            return ContainerTradeResult.Failure("Insufficient funds")

        // Step 1: Withdraw from player
        if (!economy.withdraw(playerUuid, shop.costAmount.toLong()))
            return ContainerTradeResult.Failure("Withdraw failed")

        // Step 2: Deposit to owner
        if (!economy.deposit(ownerUuid, shop.costAmount.toLong())) {
            // ROLLBACK: refund player
            economy.deposit(playerUuid, shop.costAmount.toLong())
            return ContainerTradeResult.CompensationFailed(
                error = "Owner deposit failed",
                compensation = "Player refunded"
            )
        }

        // Step 3: Remove item from container
        containerInv.removeItem(sellStack.clone())

        // Step 4: Give item to player
        val remainder = player.inventory.addItem(sellStack.clone())
        if (remainder.isNotEmpty()) {
            // ROLLBACK: return item to container, refund player, debit owner
            containerInv.addItem(sellStack)
            economy.withdraw(ownerUuid, shop.costAmount.toLong())
            economy.deposit(playerUuid, shop.costAmount.toLong())
            return ContainerTradeResult.CompensationFailed(
                error = "Inventory full",
                compensation = "Trade reversed"
            )
        }

        Bukkit.getPluginManager().callEvent(
            net.badgersmc.em.events.PostShopTransactionEvent(
                buyer = player,
                landlordId = ownerUuid,
                item = sellStack,
                quantity = shop.sellAmount,
                pricePaid = shop.costAmount.toDouble()
            )
        )

        return ContainerTradeResult.Success("Bought ${shop.sellAmount}x for ${shop.costAmount}")
    }

    /**
     * Resolves the owner UUID from a stall, handling SOLO ownership.
     * Returns null for GUILD or NONE owners, or if the UUID is malformed.
     */
    private fun resolveOwnerUuid(stall: net.badgersmc.em.domain.stall.Stall): UUID? {
        return when (stall.owner.type) {
            OwnerType.SOLO -> try {
                UUID.fromString(stall.owner.id)
            } catch (_: IllegalArgumentException) {
                null
            }
            OwnerType.GUILD -> null
            OwnerType.NONE -> null
        }
    }

    /**
     * Gets the container block at the shop's stored coordinates.
     * Made [open] so tests can override without mocking Bukkit statics.
     */
    open protected fun getContainer(shop: Shop): Container? {
        val world = Bukkit.getWorld(shop.containerWorld) ?: return null
        val block = world.getBlockAt(shop.containerX, shop.containerY, shop.containerZ)
        return block.state as? Container
    }

    /**
     * Deserializes a base64-encoded ItemStack.
     * Made [open] so tests can override without needing a real Bukkit runtime.
     */
    open protected fun deserializeStack(base64: String): ItemStack? {
        return try {
            val bytes = Base64.getDecoder().decode(base64)
            val stream = java.io.ByteArrayInputStream(bytes)
            org.bukkit.util.io.BukkitObjectInputStream(stream).readObject() as ItemStack
        } catch (_: Exception) {
            null
        }
    }
}