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
import java.util.UUID
import kotlin.math.roundToLong

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
    private val tradePolicy: GuildTradePolicyService? = null,
    private val shopVault: ShopVaultService? = null,
) {
    fun executeBuy(shop: Shop, playerUuid: UUID): ContainerTradeResult {
        if (shop.frozen) return ContainerTradeResult.Failure("This shop is frozen")
        if (shop.sellAmount <= 0 || shop.costAmount <= 0) return ContainerTradeResult.Failure("Invalid trade amounts")
        val preconditions = buyPreconditions(shop, playerUuid)
        if (preconditions.result != null) return preconditions.result!!
        val (effectiveCost, policyFailure) = resolveEffectiveCost(shop, playerUuid, shop.costAmount.toLong(), preconditions.ctx!!.guildId)
        if (policyFailure != null) return policyFailure
        if (!canAffordShopCost(preconditions.ctx!!.guildId, preconditions.ownerUuid!!, effectiveCost)) {
            return guildPaymentFailure(preconditions.ctx!!.guildId, "Shop can't afford this")
        }
        return executeBuyTransaction(shop, preconditions.ctx!!, preconditions.sellStack!!, effectiveCost)
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
        if (!inventoryHasAtLeast(player.inventory, sellStack, shop.sellAmount))
            return BuyPreconditions(result = ContainerTradeResult.Failure("You don't have the items to sell"))
        val container = getContainer(shop)
            ?: return BuyPreconditions(result = ContainerTradeResult.Failure("Container missing"))
        return BuyPreconditions(ownerUuid, TradeContext(ownerUuid, resolveGuildUuid(stall), player, container.inventory), sellStack)
    }

    private fun executeBuyTransaction(
        shop: Shop,
        ctx: TradeContext,
        sellStack: ItemStack,
        effectiveCost: Long,
    ): ContainerTradeResult {
        val removalResult = ctx.player.inventory.removeItem(sellStack.clone())
        if (removalResult.isNotEmpty()) return ContainerTradeResult.Failure("Not enough items in inventory")

        val remainder = ctx.containerInv.addItem(sellStack.clone())
        if (remainder.isNotEmpty()) {
            undoPartialInsert(ctx.containerInv, ctx.player.inventory, sellStack, remainder)
            return ContainerTradeResult.Failure("Container is full")
        }

        return processBuyPayment(shop, ctx, sellStack, effectiveCost)
    }

    /** Reverses a partial container insertion — removes what was added, returns original items. */
    private fun undoPartialInsert(containerInv: Inventory, playerInv: Inventory, sellStack: ItemStack, remainder: Map<Int, ItemStack>) {
        val inserted = sellStack.amount - remainder.values.sumOf { it.amount }
        val toRemove = sellStack.clone().apply { amount = inserted }
        containerInv.removeItem(toRemove)
        playerInv.addItem(sellStack)
    }

    /** Handles payment flow: withdraw from shop owner → deposit to player. */
    private fun processBuyPayment(shop: Shop, ctx: TradeContext, sellStack: ItemStack, cost: Long): ContainerTradeResult {
        val guildId = ctx.guildId
        if (cost > 0L) {
            if (!withdrawFromShop(guildId, ctx.ownerUuid, cost)) {
                return buyPaymentWithdrawFailed(ctx, sellStack, guildId)
            }
            if (!economy.deposit(ctx.player.uniqueId, cost)) {
                return buyPaymentDepositFailed(ctx, sellStack, guildId, cost)
            }
        }
        fireTransactionEvent(TransactionEventData(ctx.player, ctx.ownerUuid, sellStack, shop.sellAmount, cost, shop.id, shop.direction))
        return ContainerTradeResult.Success("Sold ${shop.sellAmount}x for $cost")
    }

    private fun buyPaymentWithdrawFailed(ctx: TradeContext, sellStack: ItemStack, guildId: UUID?): ContainerTradeResult {
        rollbackContainerAndPlayer(ctx.containerInv, ctx.player, sellStack)
        return ContainerTradeResult.CompensationFailed(
            error = guildPaymentFailure(guildId, "Owner payment failed").reason,
            compensation = "Item returned"
        )
    }

    private fun buyPaymentDepositFailed(ctx: TradeContext, sellStack: ItemStack, guildId: UUID?, cost: Long): ContainerTradeResult {
        val refunded = refundShop(guildId, ctx.ownerUuid, cost)
        rollbackContainerAndPlayer(ctx.containerInv, ctx.player, sellStack)
        return ContainerTradeResult.CompensationFailed(
            error = "Player deposit failed",
            compensation = if (refunded) "Full rollback" else "Partial rollback — shop refund failed"
        )
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
        val (_, policyFailure) = resolveEffectiveCost(shop, playerUuid, 0L, preconditions.ctx!!.guildId)
        if (policyFailure != null) return policyFailure
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
        if (!inventoryHasAtLeast(container.inventory, sellStack, shop.sellAmount))
            return SellPreconditions(result = ContainerTradeResult.Failure("Out of stock"))
        return SellPreconditions(TradeContext(ownerUuid, resolveGuildUuid(stall), player, container.inventory), sellStack)
    }

    @Suppress("ReturnCount")
    private fun executeSellTransaction(
        shop: Shop, playerUuid: UUID, ctx: TradeContext, sellStack: ItemStack
    ): ContainerTradeResult {
        val (cost, policyFailure) = resolveEffectiveCost(shop, playerUuid, shop.costAmount.toLong(), ctx.guildId)
        if (policyFailure != null) return policyFailure

        if (!inventoryCanFit(ctx.player.inventory, sellStack, shop.sellAmount)) {
            return ContainerTradeResult.Failure("Inventory full")
        }
        if (economy.balance(playerUuid) < cost) return ContainerTradeResult.Failure("Insufficient funds")

        // Remove stock from container *before* charging player — the pre-check
        // is a snapshot; the container could change in the meantime.
        val removalResult = ctx.containerInv.removeItem(sellStack.clone())
        if (removalResult.isNotEmpty()) {
            return ContainerTradeResult.Failure("Stock mismatch — container changed")
        }

        if (cost > 0L && !economy.withdraw(playerUuid, cost)) {
            ctx.containerInv.addItem(sellStack.clone())
            return ContainerTradeResult.Failure("Withdraw failed")
        }

        val guildId = ctx.guildId
        if (cost > 0L) {
            val depositSuccess = depositToShop(guildId, ctx.ownerUuid, cost)
            if (!depositSuccess) {
                ctx.containerInv.addItem(sellStack.clone())
                val playerRefunded = economy.deposit(playerUuid, cost)
                return ContainerTradeResult.CompensationFailed(
                    error = guildPaymentFailure(guildId, "Owner deposit failed").reason,
                    compensation = if (playerRefunded) "Player refunded" else "Partial compensation — player refund failed"
                )
            }
        }

        val remainder = ctx.player.inventory.addItem(sellStack.clone())
        if (remainder.isNotEmpty()) {
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
        if (!inventoryHasAtLeast(player.inventory, costStack, shop.costAmount)) return null
        val container = getContainer(shop) ?: return null
        if (!inventoryHasAtLeast(container.inventory, sellStack, shop.sellAmount)) return null
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

    /** Re-adds the portion of [stack] that was actually removed, given [leftover] from removeItem. */
    private fun restorePartial(inv: Inventory, stack: ItemStack, leftover: Map<Int, ItemStack>) {
        val taken = stack.amount - leftover.values.sumOf { it.amount }
        if (taken > 0) inv.addItem(stack.clone().apply { amount = taken })
    }

    private fun executeBarterTransaction(
        shop: Shop, ctx: TradeContext, sellStack: ItemStack, costStack: ItemStack
    ): ContainerTradeResult {
        // Remove cost items from player, check for partial failure
        val costLeftover = ctx.player.inventory.removeItem(costStack.clone())
        if (costLeftover.isNotEmpty()) {
            restorePartial(ctx.player.inventory, costStack, costLeftover)
            return ContainerTradeResult.Failure("Cannot afford cost — missing items")
        }
        // Remove sell items from container, check for partial failure
        val sellLeftover = ctx.containerInv.removeItem(sellStack.clone())
        if (sellLeftover.isNotEmpty()) {
            // Return cost items that were already removed
            ctx.player.inventory.addItem(costStack.clone())
            // Return any sell items that were partially removed
            restorePartial(ctx.containerInv, sellStack, sellLeftover)
            return ContainerTradeResult.Failure("Out of stock — container has fewer items than listed")
        }
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

    /**
     * Executes a barter trade using a cost item already placed into a GUI slot.
     * The cost item has already been removed from the player's inventory — it sits
     * in the GUI inventory, and the caller manages the slot lifecycle.  This method
     * only consumes the cost conceptually and deposits it to the owner's vault.
     *
     * @param shop      the barter (TRADE-direction) shop
     * @param playerUuid the clicking player's UUID
     * @param placedCost the cost ItemStack sitting in the GUI placement slot (not cloned)
     * @param multiplier the number of trade-units to execute at once
     */
    fun executeTradeWithItem(
        shop: Shop, playerUuid: UUID, placedCost: ItemStack, multiplier: Int
    ): ContainerTradeResult {
        if (shop.frozen) return ContainerTradeResult.Failure("This shop is frozen")
        if (shop.sellAmount <= 0 || shop.costAmount <= 0) return ContainerTradeResult.Failure("Invalid trade amounts")
        if (shopVault == null) return ContainerTradeResult.Failure("Vault unavailable")
        val pre = slotTradePreconditions(shop, playerUuid)
        if (pre.result != null) return pre.result!!
        val amounts = SlotTradeAmounts(shop.sellAmount * multiplier, shop.costAmount * multiplier)
        val validFail = validateSlotTrade(shop, pre.ctx!!, placedCost, amounts, playerUuid)
        if (validFail != null) return validFail
        return executeSlotTradeTransfer(pre.ctx, shop, placedCost, amounts)
    }

    private fun validateSlotTrade(
        shop: Shop, ctx: SlotTradeContext, placedCost: ItemStack, amounts: SlotTradeAmounts, playerUuid: UUID
    ): ContainerTradeResult.Failure? {
        val expectedCost = deserializeStack(shop.costItem)
            ?: return ContainerTradeResult.Failure("Invalid item")
        if (!placedCost.isSimilar(expectedCost))
            return ContainerTradeResult.Failure("Wrong trade item")
        if (placedCost.amount < amounts.cost)
            return ContainerTradeResult.Failure("Cannot afford cost — need ${amounts.cost}, have ${placedCost.amount}")
        if (!inventoryHasAtLeast(ctx.container.inventory, ctx.sellStack, amounts.sell))
            return ContainerTradeResult.Failure("Out of stock")
        if (!inventoryCanFit(ctx.player.inventory, ctx.sellStack, amounts.sell))
            return ContainerTradeResult.Failure("Inventory full")
        return checkGuildPolicy(shop, ctx.stall, playerUuid)
    }

    private data class SlotTradeAmounts(val sell: Int, val cost: Int)

    private data class SlotTradeContext(
        val player: Player,
        val container: Container,
        val sellStack: ItemStack,
        val ownerUuid: UUID,
        val stall: net.badgersmc.em.domain.stall.Stall,
    )

    private data class SlotTradePreconditions(
        val ctx: SlotTradeContext? = null,
        val result: ContainerTradeResult.Failure? = null,
    )

    private fun slotTradePreconditions(shop: Shop, playerUuid: UUID): SlotTradePreconditions {
        val (ownerUuid, stall) = resolveStallOwner(shop)
            ?: return SlotTradePreconditions(result = ContainerTradeResult.Failure("Stall not found"))
        val player = getPlayer(playerUuid)
            ?: return SlotTradePreconditions(result = ContainerTradeResult.Failure("Player offline"))
        val (container, sellStack) = resolveContainerStock(shop)
            ?: return SlotTradePreconditions(result = ContainerTradeResult.Failure("Container unavailable"))
        return SlotTradePreconditions(ctx = SlotTradeContext(player, container, sellStack, ownerUuid, stall))
    }

    private fun resolveContainerStock(shop: Shop): Pair<Container, ItemStack>? {
        val container = getContainer(shop) ?: return null
        val sellStack = buildSellStack(shop) ?: return null
        return Pair(container, sellStack)
    }

    private fun checkGuildPolicy(
        shop: Shop, stall: net.badgersmc.em.domain.stall.Stall, playerUuid: UUID
    ): ContainerTradeResult.Failure? {
        val guildId = resolveGuildUuid(stall)
        val (_, policyFailure) = resolveEffectiveCost(shop, playerUuid, 0L, guildId)
        return policyFailure
    }

    @Suppress("ReturnCount")
    private fun executeSlotTradeTransfer(ctx: SlotTradeContext, shop: Shop, placedCost: ItemStack, amounts: SlotTradeAmounts): ContainerTradeResult {
        val requestedSell = ctx.sellStack.clone().apply { amount = amounts.sell }
        val sellLeftover = ctx.container.inventory.removeItem(requestedSell)
        if (sellLeftover.isNotEmpty()) {
            restorePartial(ctx.container.inventory, requestedSell, sellLeftover)
            return ContainerTradeResult.Failure("Stock mismatch — container changed")
        }
        val remainder = ctx.player.inventory.addItem(ctx.sellStack.clone().apply { amount = amounts.sell })
        if (remainder.isNotEmpty()) {
            val received = amounts.sell - remainder.values.sumOf { it.amount }
            ctx.player.inventory.removeItem(ctx.sellStack.clone().apply { amount = received })
            ctx.container.inventory.addItem(requestedSell)
            return ContainerTradeResult.CompensationFailed(error = "Inventory full", compensation = "Trade reversed")
        }
        shopVault!!.deposit(ctx.ownerUuid, placedCost.clone().apply { amount = amounts.cost }, amounts.cost)
        fireTransactionEvent(TransactionEventData(ctx.player, ctx.ownerUuid, ctx.sellStack, amounts.sell, 0, shop.id, shop.direction))
        return ContainerTradeResult.Success("Traded ${amounts.sell}x for ${amounts.cost}x")
    }

    protected open fun getContainer(shop: Shop): Container? {
        val world = Bukkit.getWorld(shop.containerWorld) ?: return null
        return world.getBlockAt(shop.containerX, shop.containerY, shop.containerZ).state as? Container
    }

    protected open fun getPlayer(uuid: UUID): Player? = Bukkit.getPlayer(uuid)

    protected open fun deserializeStack(base64: String): ItemStack? = ItemStackSerializer.deserialize(base64)

    protected open fun inventoryHasAtLeast(inventory: Inventory, template: ItemStack, amount: Int): Boolean =
        ItemStackMatch.containsAtLeast(inventory, template, amount)

    protected open fun inventoryCanFit(inventory: Inventory, template: ItemStack, amount: Int): Boolean =
        ItemStackMatch.canFit(inventory, template, amount)

    private fun resolveEffectiveCost(
        shop: Shop,
        playerUuid: UUID,
        baseCost: Long,
        guildId: UUID?,
    ): Pair<Long, ContainerTradeResult.Failure?> {
        val ownerGuildId = guildId?.toString() ?: return baseCost to null
        val policy = tradePolicy ?: return baseCost to null
        return when (val stance = policy.stanceFor(ownerGuildId, playerUuid, shop.direction)) {
            is GuildTradePolicyService.TradeStance.Embargoed ->
                0L to ContainerTradeResult.Failure("Your guild is embargoed from trading here")
            is GuildTradePolicyService.TradeStance.Allowed -> {
                val adjusted = (baseCost * stance.factor).roundToLong().coerceAtLeast(0L)
                adjusted to null
            }
        }
    }

    private fun guildPaymentFailure(guildId: UUID?, defaultMessage: String): ContainerTradeResult.Failure {
        if (guildId != null && guildProvider == null) {
            return ContainerTradeResult.Failure("Guild bank is unavailable")
        }
        return ContainerTradeResult.Failure(defaultMessage)
    }
}
