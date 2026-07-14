package net.badgersmc.em.interaction

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.enchantments.Enchantment
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack

object MenuItems {
    /** Enchanted raw gold ingot with hidden enchants for currency display glint. */
    fun currencyIcon(name: Component, lore: List<Component> = emptyList()): ItemStack {
        val item = ItemStack(Material.RAW_GOLD)
        val meta = item.itemMeta ?: return item
        meta.displayName(name.decoration(TextDecoration.ITALIC, false))
        if (lore.isNotEmpty()) meta.lore(lore.map { it.decoration(TextDecoration.ITALIC, false) })
        meta.addEnchant(Enchantment.UNBREAKING, 1, true)
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS)
        item.itemMeta = meta
        return item
    }
}
