package net.badgersmc.em.application

import net.badgersmc.em.domain.ports.EconomyProvider
import net.badgersmc.em.domain.ports.GuildProvider
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

sealed class ContainerTradeResult {
    data class Success(val message: String) : ContainerTradeResult()
    data class Failure(val reason: String) : ContainerTradeResult()
    data class CompensationFailed(val error: String, val compensation: String) : ContainerTradeResult()
}

@Service
open class ContainerTradeService(
    private val stallRepository: StallRepository,
    private val economy: EconomyProvider,
    private val guildProvider: GuildProvider?,
    private val logger: Logger
) {
    fun executeBuy(shop: Shop, playerUuid: UUID): ContainerTradeResult {
        if (shop.frozen) return ContainerTradeResult.Failure("This shop is frozen")
        if (shop.sellAmount <= 0 || shop.costAmount <= 0) {
            return ContainerTradeResult.Failure("Invalid trade amounts")
        }

        val stall = stallRepository.findById(StallId(shop.stallId))
            ?: return ContainerTradeResult.Failure("Stall not found")

        val ownerUuid = resolveOwnerUuid(stall)
            ?: return ContainerTradeResult.Failure("Invalid owner")

        val player = Bukkit.getPlayer(playerUuid)
            ?: return ContainerTradeResult.Failure("Player not online")

        val sellStack = deserializeStack(shop.sellItem)?.apply { amount = shop.sellAmount }
            ?: return ContainerTradeResult.Failure("Invalid item")

        if (!player.inventory.containsAtLeast(sellStack, shop.sellAmount))
            return ContainerTradeResult.Failure("You don't have the items to sell")

        val cost = shop.costAmount.toLong()
        val guildId = shop.guildId
        if (guildId != null) {
            if (guildProvider == null || guildProvider.bankBalance(guildId.toString()) < cost)
                return ContainerTradeResult.Failure("Shop guild can't afford this")
        } else {
            if (economy.balance(ownerUuid) < cost)
                return ContainerTradeResult.Failure("Shop owner can't afford this")
        }

        val container = getContainer(shop)
            ?: return ContainerTradeResult.Failure("Container missing")
        val containerInv = container.inventory

        // Step 1: Remove item from player
        val removalResult = player.inventory.removeItem(sellStack.clone())
        if (removalResult.isNotEmpty()) {
            return ContainerTradeResult.Failure("Not enough items in inventory")
        }

        // Step 2: Add item to container
        val remainder = containerInv.addItem(sellStack.clone())
        if (remainder.isNotEmpty()) {
            player.inventory.addItem(sellStack)
            return ContainerTradeResult.Failure("Container is full")
        }

        // Step 3: Withdraw from owner or guild bank
        val withdrawSuccess = if (guildId != null) {
            guildProvider?.bankWithdraw(guildId.toString(), cost) ?: false
        } else {
            economy.withdraw(ownerUuid, cost)
        }
        if (!withdrawSuccess) {
            val containerLeftovers = containerInv.removeItem(sellStack)
            val playerLeftovers = player.inventory.addItem(sellStack)
            val compensation = if (containerLeftovers.isEmpty() && playerLeftovers.isEmpty()) {
                "Item returned to container and player"
            } else {
                "Partial item return — state may be inconsistent"
            }
            return ContainerTradeResult.CompensationFailed(
                error = "Owner payment failed",
                compensation = compensation
            )
        }

        // Step 4: Deposit to player
        if (!economy.deposit(playerUuid, cost)) {
            val refundSuccess = if (guildId != null) {
                guildProvider?.bankDeposit(guildId.toString(), cost) ?: false
            } else {
                economy.deposit(ownerUuid, cost)
            }
            val containerLeftovers = containerInv.removeItem(sellStack)
            val playerLeftovers = player.inventory.addItem(sellStack)
            val compensation = if (refundSuccess && containerLeftovers.isEmpty() && playerLeftovers.isEmpty()) {
                "Full rollback"
            } else {
                "Partial rollback — state may be inconsistent"
            }
            return ContainerTradeResult.CompensationFailed(
                error = "Player deposit failed",
                compensation = compensation
            )
        }

        Bukkit.getPluginManager().callEvent(
            net.badgersmc.em.events.PostShopTransactionEvent(
                buyer = player,
                landlordId = ownerUuid,
                item = sellStack,
                quantity = shop.sellAmount,
                pricePaid = cost.toDouble()
            )
        )

        return ContainerTradeResult.Success("Sold ${shop.sellAmount}x for $cost")
    }

    fun executeSell(shop: Shop, playerUuid: UUID): ContainerTradeResult {
        if (shop.frozen) return ContainerTradeResult.Failure("This shop is frozen")
        if (shop.sellAmount <= 0 || shop.costAmount <= 0) {
            return ContainerTradeResult.Failure("Invalid trade amounts")
        }

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

        if (!containerInv.containsAtLeast(sellStack, shop.sellAmount))
            return ContainerTradeResult.Failure("Out of stock")

        val cost = shop.costAmount.toLong()
        val guildId = shop.guildId

        if (economy.balance(playerUuid) < cost)
            return ContainerTradeResult.Failure("Insufficient funds")

        if (!economy.withdraw(playerUuid, cost))
            return ContainerTradeResult.Failure("Withdraw failed")

        val depositSuccess = if (guildId != null) {
            guildProvider?.bankDeposit(guildId.toString(), cost) ?: false
        } else {
            economy.deposit(ownerUuid, cost)
        }
        if (!depositSuccess) {
            val refundResult = economy.deposit(playerUuid, cost)
            val compensation = if (refundResult) {
                "Player refunded"
            } else {
                "Player refund may have failed — state may be inconsistent"
            }
            return ContainerTradeResult.CompensationFailed(
                error = "Owner deposit failed",
                compensation = compensation
            )
        }

        containerInv.removeItem(sellStack.clone())

        val remainder = player.inventory.addItem(sellStack.clone())
        if (remainder.isNotEmpty()) {
            val containerLeftovers = containerInv.addItem(sellStack)
            val ownerDebitSuccess = if (guildId != null) {
                guildProvider?.bankWithdraw(guildId.toString(), cost) ?: false
            } else {
                economy.withdraw(ownerUuid, cost)
            }
            val playerRefundResult = economy.deposit(playerUuid, cost)
            val compensation = if (containerLeftovers.isEmpty() && ownerDebitSuccess && playerRefundResult) {
                "Trade reversed"
            } else {
                "Partial trade reversal — state may be inconsistent"
            }
            return ContainerTradeResult.CompensationFailed(
                error = "Inventory full",
                compensation = compensation
            )
        }

        Bukkit.getPluginManager().callEvent(
            net.badgersmc.em.events.PostShopTransactionEvent(
                buyer = player,
                landlordId = ownerUuid,
                item = sellStack,
                quantity = shop.sellAmount,
                pricePaid = cost.toDouble()
            )
        )

        return ContainerTradeResult.Success("Bought ${shop.sellAmount}x for $cost")
    }

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

    open protected fun getContainer(shop: Shop): Container? {
        val world = Bukkit.getWorld(shop.containerWorld) ?: return null
        val block = world.getBlockAt(shop.containerX, shop.containerY, shop.containerZ)
        return block.state as? Container
    }

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