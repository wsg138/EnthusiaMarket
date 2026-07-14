package net.badgersmc.em.websync

import net.badgersmc.em.application.ItemStackSerializer
import net.badgersmc.em.domain.ports.EconomyProvider
import net.badgersmc.em.domain.ports.GuildProvider
import net.badgersmc.em.domain.shop.Shop
import net.badgersmc.em.domain.shop.SignDirection
import net.badgersmc.em.domain.stall.OwnerType
import net.badgersmc.em.domain.stall.Stall
import net.badgersmc.em.domain.stall.StallState
import org.bukkit.Bukkit
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import java.util.UUID

fun interface LoadedContainerAccess {
    fun inventory(shop: Shop): Inventory?
}

class BukkitLoadedContainerAccess : LoadedContainerAccess {
    override fun inventory(shop: Shop): Inventory? {
        val world = Bukkit.getWorld(shop.containerWorld) ?: return null
        if (!world.isChunkLoaded(shop.containerX shr 4, shop.containerZ shr 4)) return null
        return (world.getBlockAt(shop.containerX, shop.containerY, shop.containerZ).state as? org.bukkit.block.Container)?.inventory
    }
}

class ShopAvailabilityCalculator(
    private val economy: EconomyProvider,
    private val guilds: GuildProvider,
    private val containers: LoadedContainerAccess = BukkitLoadedContainerAccess(),
    private val deserialize: (String) -> ItemStack? = ItemStackSerializer::deserialize,
) {
    fun availableTrades(shop: Shop, stall: Stall): Int {
        if (shop.frozen || stall.state != StallState.OWNED) return 0
        return when (shop.direction) {
            SignDirection.SELL, SignDirection.TRADE -> shop.stockCount / shop.sellAmount
            SignDirection.BUY -> buyAvailability(shop, stall)
        }.coerceIn(0, Int.MAX_VALUE)
    }

    private fun buyAvailability(shop: Shop, stall: Stall): Int {
        val funded = fundedTrades(shop, stall)
        if (funded <= 0) return 0
        val inventory = containers.inventory(shop) ?: return 0
        val template = deserialize(shop.sellItem) ?: return 0
        val capacityItems = acceptingCapacity(inventory, template)
        return minOf(funded, capacityItems / shop.sellAmount)
    }

    private fun fundedTrades(shop: Shop, stall: Stall): Int {
        val balance = when (stall.owner.type) {
            OwnerType.SOLO -> runCatching { economy.balance(UUID.fromString(stall.owner.id)) }.getOrDefault(0)
            OwnerType.GUILD -> runCatching { guilds.bankBalance(stall.owner.id) }.getOrDefault(0)
            OwnerType.NONE -> 0
        }
        return (balance / shop.costAmount.toLong()).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun acceptingCapacity(inventory: Inventory, template: ItemStack): Int = inventory.storageContents.sumOf { current ->
        when {
            current == null || current.type.isAir -> minOf(template.maxStackSize, inventory.maxStackSize)
            current.isSimilar(template) -> (minOf(current.maxStackSize, inventory.maxStackSize) - current.amount).coerceAtLeast(0)
            else -> 0
        }
    }
}
