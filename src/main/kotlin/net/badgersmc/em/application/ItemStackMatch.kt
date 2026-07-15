package net.badgersmc.em.application

import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack

/** Byte-exact item matching for shop stock/cost checks (ignores stack size). */
object ItemStackMatch {

    fun matches(a: ItemStack, b: ItemStack): Boolean =
        normalizedBytes(a).contentEquals(normalizedBytes(b))

    fun countIn(inventory: Inventory, template: ItemStack): Int {
        val templateBytes = normalizedBytes(template)
        return inventory.storageContents.filterNotNull()
            .filter { bytesMatch(it, templateBytes) }
            .sumOf { it.amount }
    }

    /** Same as [countIn] but the template is raw serialized bytes (base64-decoded
     *  from [ItemStackSerializer.serialize]) — avoids the deserialize→serialize
     *  round-trip which can drop enchantment NBT in some Paper versions. */
    fun countInBytes(inventory: Inventory, templateBytes: ByteArray): Int =
        inventory.storageContents.filterNotNull()
            .filter { bytesMatch(it, templateBytes) }
            .sumOf { it.amount }

    /** Counts inventory items matching [template] via [ItemStack.isSimilar],
     *  which compares material, damage, and ItemMeta (enchantments, name, lore)
     *  but ignores repair cost. Use for enchanted items where users may have
     *  anvilled/merged stacks with different repair costs on the same item. */
    fun countSimilar(inventory: Inventory, template: ItemStack): Int =
        inventory.storageContents.filterNotNull()
            .filter { it.isSimilar(template) }
            .sumOf { it.amount }

    fun containsAtLeast(inventory: Inventory, template: ItemStack, amount: Int): Boolean =
        countIn(inventory, template) >= amount

    fun containsAtLeastSimilar(inventory: Inventory, template: ItemStack, amount: Int): Boolean =
        countSimilar(inventory, template) >= amount

    fun canFit(inventory: Inventory, template: ItemStack, amount: Int): Boolean {
        if (amount <= 0) return false
        val templateBytes = normalizedBytes(template)
        var remaining = amount
        val maxStack = template.maxStackSize
        for (slot in inventory.storageContents) {
            if (remaining <= 0) break
            remaining -= freeSpaceInSlot(slot, templateBytes, maxStack)
        }
        return remaining <= 0
    }

    fun canFitSimilar(inventory: Inventory, template: ItemStack, amount: Int): Boolean {
        if (amount <= 0) return false
        val templateCopy = template.clone()
        var remaining = amount
        val maxStack = template.maxStackSize
        for (slot in inventory.storageContents) {
            if (remaining <= 0) break
            remaining -= freeSpaceSimilar(slot, templateCopy, maxStack)
        }
        return remaining <= 0
    }

    private fun freeSpaceSimilar(slot: ItemStack?, template: ItemStack, maxStack: Int): Int {
        if (slot == null || slot.type.isAir) return maxStack
        return if (slot.isSimilar(template)) (maxStack - slot.amount).coerceAtLeast(0) else 0
    }

    private fun freeSpaceInSlot(slot: ItemStack?, templateBytes: ByteArray, maxStack: Int): Int {
        if (slot == null || slot.type.isAir) return maxStack
        return if (bytesMatch(slot, templateBytes)) (maxStack - slot.amount).coerceAtLeast(0) else 0
    }

    private fun bytesMatch(stack: ItemStack, templateBytes: ByteArray): Boolean =
        normalizedBytes(stack).contentEquals(templateBytes)

    private fun normalizedBytes(stack: ItemStack): ByteArray {
        val single = stack.clone()
        single.amount = 1
        return single.serializeAsBytes()
    }
}