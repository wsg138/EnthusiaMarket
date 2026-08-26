package net.badgersmc.em.application

import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.Damageable

/** Sets damage to 0 on [Damageable] items that report no damage,
 *  so absent-damage and damage=0 produce identical serialized bytes.
 *  repair_cost, enchantments, names, lore, and custom data are untouched. */
internal fun normalizeDamage(stack: ItemStack) {
    val meta = stack.itemMeta
    if (meta is Damageable && !meta.hasDamage()) {
        meta.damage = 0
        stack.itemMeta = meta
    }
}

/** Byte-exact item matching for shop stock/cost checks (ignores stack size). */
@Suppress("TooManyFunctions")
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
     *  but ignores repair cost. Also normalises Damage tags: freshly crafted
     *  items lack a Damage tag (null in CraftMetaItem) while anvil-combined
     *  items carry an explicit Damage:0 — [isSimilar] treats null != 0 and
     *  rejects them as non-matching even when both are undamaged. */
    fun countSimilar(inventory: Inventory, template: ItemStack): Int =
        inventory.storageContents.filterNotNull()
            .filter { isSimilarIgnoringDamageNullZero(it, template) }
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

    /** Returns a human-readable list of serialized-map components that differ
     *  between [a] and [b].  Keys present only in one side, or with differing
     *  values, are reported.  Useful for debugging byte-exact mismatches. */
    fun mismatchDebug(a: ItemStack, b: ItemStack): List<String> {
        val ma = normalizeMap(a)
        val mb = normalizeMap(b)
        val diffs = mutableListOf<String>()
        val allKeys = (ma.keys + mb.keys).toSortedSet()
        for (key in allKeys) {
            val va = ma[key]
            val vb = mb[key]
            when {
                va == null && vb != null -> diffs += "+ $key = $vb"
                va != null && vb == null -> diffs += "- $key = $va"
                va != vb                 -> diffs += "~ $key: $va → $vb"
            }
        }
        return diffs
    }

    /** Serialize to a normalized map (stack size stripped, damage normalized). */
    private fun normalizeMap(stack: ItemStack): Map<String, Any> {
        val single = stack.clone()
        single.amount = 1
        normalizeDamage(single)
        return single.serialize()
    }

    private fun freeSpaceSimilar(slot: ItemStack?, template: ItemStack, maxStack: Int): Int {
        if (slot == null || slot.type.isAir) return maxStack
        return if (isSimilarIgnoringDamageNullZero(slot, template)) (maxStack - slot.amount).coerceAtLeast(0) else 0
    }

    private fun freeSpaceInSlot(slot: ItemStack?, templateBytes: ByteArray, maxStack: Int): Int {
        if (slot == null || slot.type.isAir) return maxStack
        return if (bytesMatch(slot, templateBytes)) (maxStack - slot.amount).coerceAtLeast(0) else 0
    }

    private fun bytesMatch(stack: ItemStack, templateBytes: ByteArray): Boolean =
        normalizedBytes(stack).contentEquals(templateBytes)

    /** Returns true when [a] and [b] are [ItemStack.isSimilar], including the
     *  edge case where both items are undamaged but one carries an explicit
     *  `Damage:0` NBT tag while the other has no Damage tag at all — Bukkit's
     *  [CraftMetaItem.equals] treats `null != 0` and rejects them. */
    internal fun isSimilarIgnoringDamageNullZero(a: ItemStack, b: ItemStack): Boolean {
        if (a.isSimilar(b)) return true
        val aMeta = a.itemMeta
        val bMeta = b.itemMeta
        if (aMeta is Damageable && bMeta is Damageable) {
            if (!aMeta.hasDamage() && !bMeta.hasDamage()) {
                // Both undamaged; force damage to 0 on both and retry.
                val aClone = a.clone()
                val bClone = b.clone()
                val aDamageMeta = aClone.itemMeta as Damageable
                aDamageMeta.damage = 0
                aClone.itemMeta = aDamageMeta
                val bDamageMeta = bClone.itemMeta as Damageable
                bDamageMeta.damage = 0
                bClone.itemMeta = bDamageMeta
                return aClone.isSimilar(bClone)
            }
        }
        return false
    }

    private fun normalizeDamage(stack: ItemStack) = net.badgersmc.em.application.normalizeDamage(stack)

    private fun normalizedBytes(stack: ItemStack): ByteArray {
        val single = stack.clone()
        single.amount = 1
        normalizeDamage(single)
        return single.serializeAsBytes()
    }
}