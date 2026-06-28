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

private data class TransactionEventData(
    val player: Player,
    val ownerUuid: UUID,
    val item: ItemStack,
    val quantity: Int,
    val cost: Long,
    val shopId: Long,
    val direction: net.badgersmc.em.domain.shop.SignDirection
)

private data class TradeContext(
    val ownerUuid: UUID,
    val guildId: UUID?,
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
@Suppress("TooManyFunctions")
open class ContainerTradeService(
    private val stallRepository: StallRepository,
    private val economy: EconomyProvider,
    private val guildProvider: GuildProvider?,
    private val shopVault: ShopVaultService? = null,
) {
    fun executeBuy(shop: Shop, playerUuid: UUID): ContainerTradeResult {
        if (shop.frozen) return ContainerTradeResult.Failure("This shop is frozen")
        if (shop.sellAmount <= 0 || shop.costAmount <= 0) return ContainerTradeResult.Failure("Invalid trade amounts")
        val preconditions = buyPreconditions(shop, playerUuid)
        if (preconditions.result != null) return preconditions.result!!
        if (!canAffordShopCost(preconditions.ctx!!.guildId, preconditions.ownerUuid!!, shop.costAmount.toLong())) return ContainerTradeResult.Failure("Shop can't afford this")
        return executeBuyTransaction(shop, playerUuid, preconditions.ctx!!, preconditions.sellStack!!)
    }

    private data class BuyPreconditions(
        val ownerUuid: UUID? = null,
        val ctx: TradeContext? = null,
        val sellStack: ItemStack? = null,
        val result: ContainerTradeResult.Failure? = null
    )

    private fun buyPreconditions(shop: Shop, playerUuid: UUID): BuyPreconditions {
        val (ownerUuid, stall) = resolveStallOwner(shop)
            ?: return BuyPreconditions(result = ContainerTradeResult.Failure("Stall not found"))
        val (player, sellStack) = resolvePlayerAndSellStack(shop, playerUuid)
            ?: return BuyPreconditions(result = ContainerTradeResult.Failure("Invalid item"))
        if (!player.inventory.containsAtLeast(sellStack, shop.sellAmount))
            return BuyPreconditions(result = ContainerTradeResult.Failure("You don't have the items to sell"))
        val container = getContainer(shop)
            ?: return BuyPreconditions(result = ContainerTradeResult.Failure("Container missing"))
        return BuyPreconditions(ownerUuid, TradeContext(ownerUuid, resolveGuildUuid(stall), player, container.inventory), sellStack)
    }

    private fun executeBuyTransaction(shop: Shop, playerUuid: UUID, ctx: TradeContext, sellStack: ItemStack): ContainerTradeResult {
        val removalResult = ctx.player.inventory.removeItem(sellStack.clone())
        if (removalResult.isNotEmpty()) return ContainerTradeResult.Failure("Not enough items in inventory")

        val remainder = ctx.containerInv.addItem(sellStack.clone())
        if (remainder.isNotEmpty()) {
            undoPartialInsert(ctx.containerInv, ctx.player.inventory, sellStack, remainder)
            return ContainerTradeResult.Failure("Container is full")
        }

        return processBuyPayment(shop, ctx, sellStack, playerUuid)
    }

    /** Reverses a partial container insertion — removes what was added, returns original items. */
    private fun undoPartialInsert(containerInv: Inventory, playerInv: Inventory, sellStack: ItemStack, remainder: Map<Int, ItemStack>) {
        val inserted = sellStack.amount - remainder.values.sumOf { it.amount }
        val toRemove = sellStack.clone().apply { amount = inserted }
        containerInv.removeItem(toRemove)
        playerInv.addItem(sellStack)
    }

    /** Handles payment flow: withdraw from shop owner → deposit to player. */
    private fun processBuyPayment(shop: Shop, ctx: TradeContext, sellStack: ItemStack, playerUuid: UUID): ContainerTradeResult {
        val cost = shop.costAmount.toLong()
        val guildId = ctx.guildId

        if (!withdrawFromShop(guildId, ctx.ownerUuid, cost)) {
            rollbackContainerAndPlayer(ctx.containerInv, ctx.player, sellStack)
            return ContainerTradeResult.CompensationFailed(error = "Owner payment failed", compensation = "Item returned")
        }

        if (!economy.deposit(playerUuid, cost)) {
            val refunded = refundShop(guildId, ctx.ownerUuid, cost)
            rollbackContainerAndPlayer(ctx.containerInv, ctx.player, sellStack)
            return ContainerTradeResult.CompensationFailed(
                error = "Player deposit failed",
                compensation = if (refunded) "Full rollback" else "Partial rollback — shop refund failed"
            )
        }

        fireTransactionEvent(TransactionEventData(ctx.player, ctx.ownerUuid, sellStack, shop.sellAmount, cost, shop.id, shop.direction))
        return ContainerTradeResult.Success("Sold ${shop.sellAmount}x for $cost")
    }

    fun executeSell(shop: Shop, playerUuid: UUID): ContainerTradeResult {
        if (shop.frozen) return ContainerTradeResult.Failure("This shop is frozen")
        if (shop.sellAmount <= 0 || shop.costAmount <= 0) return ContainerTradeResult.Failure("Invalid trade amounts")
        val preconditions = sellPreconditions(shop, playerUuid)
        if (preconditions.result != null) return preconditions.result!!
        return executeSellTransaction(shop, playerUuid, preconditions.ctx!!, preconditions.sellStack!!)
    }

    /**
     * Executes a barter trade (TRADE direction). Item-for-item exchange between
     * player inventory and container, with economy-based cost bypassed. REQ-298.
     */
    fun executeTrade(shop: Shop, playerUuid: UUID): ContainerTradeResult {
        if (shop.frozen) return ContainerTradeResult.Failure("This shop is frozen")
        if (shop.sellAmount <= 0 || shop.costAmount <= 0) return ContainerTradeResult.Failure("Invalid trade amounts")
        // Barter trades exchange items without economy transactions.
        // Player gives costItem, receives sellItem from the container.
        val preconditions = barterPreconditions(shop, playerUuid)
        if (preconditions.result != null) return preconditions.result!!
        return executeBarterTransaction(shop, preconditions.ctx!!, preconditions.sellStack!!, preconditions.costStack!!)
    }

    private data class SellPreconditions(
        val ctx: TradeContext? = null,
        val sellStack: ItemStack? = null,
        val result: ContainerTradeResult.Failure? = null
    )

    private fun sellPreconditions(shop: Shop, playerUuid: UUID): SellPreconditions {
        val (ownerUuid, stall) = resolveStallOwner(shop)
            ?: return SellPreconditions(result = ContainerTradeResult.Failure("Stall not found"))
        val (player, sellStack) = resolvePlayerAndSellStack(shop, playerUuid)
            ?: return SellPreconditions(result = ContainerTradeResult.Failure("Invalid item"))
        val container = getContainer(shop)
            ?: return SellPreconditions(result = ContainerTradeResult.Failure("Container missing"))
        if (!container.inventory.containsAtLeast(sellStack, shop.sellAmount))
            return SellPreconditions(result = ContainerTradeResult.Failure("Out of stock"))
        return SellPreconditions(TradeContext(ownerUuid, resolveGuildUuid(stall), player, container.inventory), sellStack)
    }

    private fun executeSellTransaction(
        shop: Shop, playerUuid: UUID, ctx: TradeContext, sellStack: ItemStack
    ): ContainerTradeResult {
        val cost = shop.costAmount.toLong()
        if (economy.balance(playerUuid) < cost) return ContainerTradeResult.Failure("Insufficient funds")
        if (!economy.withdraw(playerUuid, cost)) return ContainerTradeResult.Failure("Withdraw failed")

        val guildId = ctx.guildId
        val depositSuccess = depositToShop(guildId, ctx.ownerUuid, cost)
        if (!depositSuccess) {
            economy.deposit(playerUuid, cost)
            return ContainerTradeResult.CompensationFailed(error = "Owner deposit failed", compensation = "Player refunded")
        }

        ctx.containerInv.removeItem(sellStack.clone())
        val remainder = ctx.player.inventory.addItem(sellStack.clone())
        if (remainder.isNotEmpty()) {
            // Pull back only what was actually accepted before rolling back the full transaction
            val received = sellStack.amount - remainder.values.sumOf { it.amount }
            val toRemove = sellStack.clone().apply { amount = received }
            ctx.player.inventory.removeItem(toRemove)
            val rolledBack = rollbackFullTransaction(guildId, ctx.ownerUuid, playerUuid, cost, ctx.containerInv, sellStack)
            val msg = if (rolledBack) {
                "Trade reversed — check your inventory"
            } else {
                "Trade rollback incomplete — contact staff"
            }
            return ContainerTradeResult.CompensationFailed(
                error = "Inventory full",
                compensation = msg
            )
        }

        fireTransactionEvent(TransactionEventData(ctx.player, ctx.ownerUuid, sellStack, shop.sellAmount, cost, shop.id, shop.direction))
        return ContainerTradeResult.Success("Bought ${shop.sellAmount}x for $cost")
    }

    private fun rollbackContainerAndPlayer(containerInv: Inventory, player: Player, stack: ItemStack) {
        containerInv.removeItem(stack)
        player.inventory.addItem(stack)
    }

    private fun rollbackFullTransaction(
        guildId: UUID?, ownerUuid: UUID, playerUuid: UUID, cost: Long,
        containerInv: Inventory, sellStack: ItemStack
    ): Boolean {
        val itemsRestored = containerInv.addItem(sellStack).isEmpty()
        val fundsReversed = if (guildId != null) {
            guildProvider?.bankWithdraw(guildId.toString(), cost) == true
        } else {
            economy.withdraw(ownerUuid, cost)
        }
        val playerRefunded = economy.deposit(playerUuid, cost)
        return itemsRestored && fundsReversed && playerRefunded
    }

    private fun canAffordShopCost(guildId: UUID?, ownerUuid: UUID, cost: Long): Boolean {
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

    private fun refundShop(guildId: UUID?, ownerUuid: UUID, cost: Long): Boolean {
        return if (guildId != null) guildProvider?.bankDeposit(guildId.toString(), cost) ?: false
        else economy.deposit(ownerUuid, cost)
    }

    private fun fireTransactionEvent(data: TransactionEventData) {
        Bukkit.getPluginManager().callEvent(
            net.badgersmc.em.events.PostShopTransactionEvent(
                buyer = data.player, landlordId = data.ownerUuid,
                item = data.item, quantity = data.quantity, pricePaid = data.cost.toDouble(),
                shopId = data.shopId, direction = data.direction
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
            OwnerType.GUILD -> try { UUID.fromString(stall.owner.id) } catch (_: IllegalArgumentException) { null }
            OwnerType.NONE -> null
        }
    }

    /** Resolves the guild UUID when the stall is guild-owned, null otherwise. */
    private fun resolveGuildUuid(stall: net.badgersmc.em.domain.stall.Stall): UUID? {
        return if (stall.owner.type == OwnerType.GUILD) {
            runCatching { UUID.fromString(stall.owner.id) }.getOrNull()
        } else null
    }

    // --- Barter trade (TRADE direction) ---

    private data class BarterPreconditions(
        val ctx: TradeContext? = null,
        val sellStack: ItemStack? = null,
        val costStack: ItemStack? = null,
        val result: ContainerTradeResult.Failure? = null
    )

    private fun barterPreconditions(shop: Shop, playerUuid: UUID): BarterPreconditions {
        val (ownerUuid, stall) = resolveStallOwner(shop)
            ?: return BarterPreconditions(result = ContainerTradeResult.Failure("Stall not found"))
        if (shopVault == null) return BarterPreconditions(result = ContainerTradeResult.Failure("Vault unavailable"))
        val (player, stacks) = resolveBarterPlayer(playerUuid, shop)
            ?: return BarterPreconditions(result = ContainerTradeResult.Failure("Invalid item"))
        val container = validateBarterStock(shop, player, stacks.first, stacks.second)
            ?: return BarterPreconditions(result = ContainerTradeResult.Failure("Out of stock"))
        return BarterPreconditions(
            TradeContext(ownerUuid, resolveGuildUuid(stall), player, container.inventory),
            stacks.first, stacks.second
        )
    }

    /** Gets online player + deserialized barter stacks, or null. Sets costStack.amount. */
    private fun resolveBarterPlayer(playerUuid: UUID, shop: Shop): Pair<Player, Pair<ItemStack, ItemStack>>? {
        val player = getPlayer(playerUuid) ?: return null
        val stacks = buildBarterStacks(shop) ?: return null
        stacks.second.amount = shop.costAmount
        return Pair(player, stacks)
    }

    /** Validates player has cost items and container has sell stock. Returns container or null. */
    private fun validateBarterStock(shop: Shop, player: Player, sellStack: ItemStack, costStack: ItemStack): Container? {
        if (!player.inventory.containsAtLeast(costStack, shop.costAmount)) return null
        val container = getContainer(shop) ?: return null
        if (!container.inventory.containsAtLeast(sellStack, shop.sellAmount)) return null
        return container
    }

    /** Returns owner UUID + stall, or null if stall/owner resolution fails. */
    private fun resolveStallOwner(shop: Shop): Pair<UUID, net.badgersmc.em.domain.stall.Stall>? {
        val stall = stallRepository.findById(StallId(shop.stallId)) ?: return null
        val ownerUuid = resolveOwnerUuid(stall) ?: return null
        return Pair(ownerUuid, stall)
    }

    /** Returns online player + deserialized sell stack, or null if either fails. */
    private fun resolvePlayerAndSellStack(shop: Shop, playerUuid: UUID): Pair<Player, ItemStack>? {
        val player = getPlayer(playerUuid) ?: return null
        val sellStack = buildSellStack(shop) ?: return null
        return Pair(player, sellStack)
    }

    /** Deserializes both sell and cost stacks, or null if either fails. */
    private fun buildBarterStacks(shop: Shop): Pair<ItemStack, ItemStack>? {
        val sellStack = buildSellStack(shop) ?: return null
        val costStack = deserializeStack(shop.costItem) ?: return null
        return Pair(sellStack, costStack)
    }

    private fun executeBarterTransaction(
        shop: Shop, ctx: TradeContext, sellStack: ItemStack, costStack: ItemStack
    ): ContainerTradeResult {
        // Remove cost items from player
        ctx.player.inventory.removeItem(costStack.clone())
        // Remove sell items from container
        ctx.containerInv.removeItem(sellStack.clone())
        // Give sell items to player
        val remainder = ctx.player.inventory.addItem(sellStack.clone())
        if (remainder.isNotEmpty()) {
            // Undo only what was actually inserted before rolling back
            val received = sellStack.amount - remainder.values.sumOf { it.amount }
            val toRemove = sellStack.clone().apply { amount = received }
            ctx.player.inventory.removeItem(toRemove)
            // Return full sell stack to container (not just accepted portion) —
            // the full sellStack was removed at line 338.
            ctx.containerInv.addItem(sellStack.clone())
            ctx.player.inventory.addItem(costStack.clone())
            return ContainerTradeResult.CompensationFailed(error = "Inventory full", compensation = "Trade reversed")
        }
        // Give cost items to owner's vault (REQ — V016 shop vault contract)
        shopVault?.deposit(ctx.ownerUuid, costStack, costStack.amount)
        fireTransactionEvent(TransactionEventData(ctx.player, ctx.ownerUuid, sellStack, shop.sellAmount, 0, shop.id, shop.direction))
        return ContainerTradeResult.Success("Traded ${shop.sellAmount}x for ${shop.costAmount}x")
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
