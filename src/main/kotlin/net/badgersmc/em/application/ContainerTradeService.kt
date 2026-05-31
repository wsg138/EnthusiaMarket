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
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import java.util.Base64
import java.util.UUID

sealed class ContainerTradeResult {
    data class Success(val message: String) : ContainerTradeResult()
    data class Failure(val reason: String) : ContainerTradeResult()
    data class CompensationFailed(val error: String, val compensation: String) : ContainerTradeResult()
}

private data class TradeContext(
    val ownerUuid: UUID,
    val player: Player,
    val containerInv: Inventory
)

/**
 * Executes buy/sell trades against container-linked shops.
 *
 * Handles item transfers between player inventory and container,
 * with economy integration for both personal and guild shops.
 */
@Service
open class ContainerTradeService(
    private val stallRepository: StallRepository,
    private val economy: EconomyProvider,
    private val guildProvider: GuildProvider?,
) {
    fun executeBuy(shop: Shop, playerUuid: UUID): ContainerTradeResult {
        if (shop.frozen) return ContainerTradeResult.Failure("This shop is frozen")
        if (shop.sellAmount <= 0 || shop.costAmount <= 0) return ContainerTradeResult.Failure("Invalid trade amounts")
        val preconditions = buyPreconditions(shop, playerUuid)
        if (preconditions.result != null) return preconditions.result!!
        if (!canAffordShopCost(shop, preconditions.ownerUuid!!)) return ContainerTradeResult.Failure("Shop can't afford this")
        return executeBuyTransaction(shop, playerUuid, preconditions.ctx!!, preconditions.sellStack!!)
    }

    private data class BuyPreconditions(
        val ownerUuid: UUID? = null,
        val ctx: TradeContext? = null,
        val sellStack: ItemStack? = null,
        val result: ContainerTradeResult.Failure? = null
    )

    private fun buyPreconditions(shop: Shop, playerUuid: UUID): BuyPreconditions {
        val stall = stallRepository.findById(StallId(shop.stallId))
            ?: return BuyPreconditions(result = ContainerTradeResult.Failure("Stall not found"))
        val ownerUuid = resolveOwnerUuid(stall)
            ?: return BuyPreconditions(result = ContainerTradeResult.Failure("Invalid owner"))
        val player = getPlayer(playerUuid)
            ?: return BuyPreconditions(result = ContainerTradeResult.Failure("Player not online"))
        val sellStack = buildSellStack(shop)
            ?: return BuyPreconditions(result = ContainerTradeResult.Failure("Invalid item"))
        if (!player.inventory.containsAtLeast(sellStack, shop.sellAmount))
            return BuyPreconditions(result = ContainerTradeResult.Failure("You don't have the items to sell"))
        val container = getContainer(shop)
            ?: return BuyPreconditions(result = ContainerTradeResult.Failure("Container missing"))
        return BuyPreconditions(ownerUuid, TradeContext(ownerUuid, player, container.inventory), sellStack)
    }

    private fun executeBuyTransaction(shop: Shop, playerUuid: UUID, ctx: TradeContext, sellStack: ItemStack): ContainerTradeResult {
        val removalResult = ctx.player.inventory.removeItem(sellStack.clone())
        if (removalResult.isNotEmpty()) return ContainerTradeResult.Failure("Not enough items in inventory")

        val remainder = ctx.containerInv.addItem(sellStack.clone())
        if (remainder.isNotEmpty()) {
            ctx.player.inventory.addItem(sellStack)
            return ContainerTradeResult.Failure("Container is full")
        }

        val cost = shop.costAmount.toLong()
        val guildId = shop.guildId

        val withdrawSuccess = withdrawFromShop(guildId, ctx.ownerUuid, cost)
        if (!withdrawSuccess) {
            rollbackContainerAndPlayer(ctx.containerInv, ctx.player, sellStack)
            return ContainerTradeResult.CompensationFailed(error = "Owner payment failed", compensation = "Item returned")
        }

        if (!economy.deposit(playerUuid, cost)) {
            refundShop(guildId, ctx.ownerUuid, cost)
            rollbackContainerAndPlayer(ctx.containerInv, ctx.player, sellStack)
            return ContainerTradeResult.CompensationFailed(error = "Player deposit failed", compensation = "Full rollback")
        }

        fireTransactionEvent(ctx.player, ctx.ownerUuid, sellStack, shop.sellAmount, cost)
        return ContainerTradeResult.Success("Sold ${shop.sellAmount}x for $cost")
    }

    fun executeSell(shop: Shop, playerUuid: UUID): ContainerTradeResult {
        if (shop.frozen) return ContainerTradeResult.Failure("This shop is frozen")
        if (shop.sellAmount <= 0 || shop.costAmount <= 0) return ContainerTradeResult.Failure("Invalid trade amounts")
        val preconditions = sellPreconditions(shop, playerUuid)
        if (preconditions.result != null) return preconditions.result!!
        return executeSellTransaction(shop, playerUuid, preconditions.ctx!!, preconditions.sellStack!!)
    }

    private data class SellPreconditions(
        val ctx: TradeContext? = null,
        val sellStack: ItemStack? = null,
        val result: ContainerTradeResult.Failure? = null
    )

    private fun sellPreconditions(shop: Shop, playerUuid: UUID): SellPreconditions {
        val stall = stallRepository.findById(StallId(shop.stallId))
            ?: return SellPreconditions(result = ContainerTradeResult.Failure("Stall not found"))
        val ownerUuid = resolveOwnerUuid(stall)
            ?: return SellPreconditions(result = ContainerTradeResult.Failure("Invalid owner"))
        val player = getPlayer(playerUuid)
            ?: return SellPreconditions(result = ContainerTradeResult.Failure("Player not online"))
        val sellStack = buildSellStack(shop)
            ?: return SellPreconditions(result = ContainerTradeResult.Failure("Invalid item"))
        val container = getContainer(shop)
            ?: return SellPreconditions(result = ContainerTradeResult.Failure("Container missing"))
        val containerInv = container.inventory
        if (!containerInv.containsAtLeast(sellStack, shop.sellAmount))
            return SellPreconditions(result = ContainerTradeResult.Failure("Out of stock"))
        return SellPreconditions(TradeContext(ownerUuid, player, containerInv), sellStack)
    }

    private fun executeSellTransaction(
        shop: Shop, playerUuid: UUID, ctx: TradeContext, sellStack: ItemStack
    ): ContainerTradeResult {
        val cost = shop.costAmount.toLong()
        if (economy.balance(playerUuid) < cost) return ContainerTradeResult.Failure("Insufficient funds")
        if (!economy.withdraw(playerUuid, cost)) return ContainerTradeResult.Failure("Withdraw failed")

        val guildId = shop.guildId
        val depositSuccess = depositToShop(guildId, ctx.ownerUuid, cost)
        if (!depositSuccess) {
            economy.deposit(playerUuid, cost)
            return ContainerTradeResult.CompensationFailed(error = "Owner deposit failed", compensation = "Player refunded")
        }

        ctx.containerInv.removeItem(sellStack.clone())
        val remainder = ctx.player.inventory.addItem(sellStack.clone())
        if (remainder.isNotEmpty()) {
            rollbackFullTransaction(guildId, ctx.ownerUuid, playerUuid, cost, ctx.containerInv, sellStack)
            return ContainerTradeResult.CompensationFailed(error = "Inventory full", compensation = "Trade reversed")
        }

        fireTransactionEvent(ctx.player, ctx.ownerUuid, sellStack, shop.sellAmount, cost)
        return ContainerTradeResult.Success("Bought ${shop.sellAmount}x for $cost")
    }

    private fun rollbackContainerAndPlayer(containerInv: Inventory, player: Player, stack: ItemStack) {
        containerInv.removeItem(stack)
        player.inventory.addItem(stack)
    }

    private fun rollbackFullTransaction(
        guildId: UUID?, ownerUuid: UUID, playerUuid: UUID, cost: Long,
        containerInv: Inventory, sellStack: ItemStack
    ) {
        containerInv.addItem(sellStack)
        if (guildId != null) guildProvider?.bankWithdraw(guildId.toString(), cost) else economy.withdraw(ownerUuid, cost)
        economy.deposit(playerUuid, cost)
    }

    private fun canAffordShopCost(shop: Shop, ownerUuid: UUID): Boolean {
        val cost = shop.costAmount.toLong()
        val guildId = shop.guildId
        return if (guildId != null) {
            guildProvider != null && guildProvider.bankBalance(guildId.toString()) >= cost
        } else {
            economy.balance(ownerUuid) >= cost
        }
    }

    private fun withdrawFromShop(guildId: UUID?, ownerUuid: UUID, cost: Long): Boolean {
        return if (guildId != null) guildProvider?.bankWithdraw(guildId.toString(), cost) ?: false
        else economy.withdraw(ownerUuid, cost)
    }

    private fun depositToShop(guildId: UUID?, ownerUuid: UUID, cost: Long): Boolean {
        return if (guildId != null) guildProvider?.bankDeposit(guildId.toString(), cost) ?: false
        else economy.deposit(ownerUuid, cost)
    }

    private fun refundShop(guildId: UUID?, ownerUuid: UUID, cost: Long) {
        if (guildId != null) guildProvider?.bankDeposit(guildId.toString(), cost) else economy.deposit(ownerUuid, cost)
    }

    private fun fireTransactionEvent(player: Player, ownerUuid: UUID, item: ItemStack, quantity: Int, cost: Long) {
        Bukkit.getPluginManager().callEvent(
            net.badgersmc.em.events.PostShopTransactionEvent(
                buyer = player, landlordId = ownerUuid,
                item = item, quantity = quantity, pricePaid = cost.toDouble()
            )
        )
    }

    private fun buildSellStack(shop: Shop): ItemStack? {
        val base = deserializeStack(shop.sellItem) ?: return null
        base.amount = shop.sellAmount
        return base
    }

    private fun resolveOwnerUuid(stall: net.badgersmc.em.domain.stall.Stall): UUID? {
        return when (stall.owner.type) {
            OwnerType.SOLO -> try { UUID.fromString(stall.owner.id) } catch (_: IllegalArgumentException) { null }
            OwnerType.GUILD, OwnerType.NONE -> null
        }
    }

    protected open fun getContainer(shop: Shop): Container? {
        val world = Bukkit.getWorld(shop.containerWorld) ?: return null
        return world.getBlockAt(shop.containerX, shop.containerY, shop.containerZ).state as? Container
    }

    protected open fun getPlayer(uuid: UUID): Player? = Bukkit.getPlayer(uuid)

    protected open fun deserializeStack(base64: String): ItemStack? {
        return try {
            val bytes = Base64.getDecoder().decode(base64)
            val stream = java.io.ByteArrayInputStream(bytes)
            org.bukkit.util.io.BukkitObjectInputStream(stream).readObject() as ItemStack
        } catch (_: Exception) {
            null
        }
    }
}
