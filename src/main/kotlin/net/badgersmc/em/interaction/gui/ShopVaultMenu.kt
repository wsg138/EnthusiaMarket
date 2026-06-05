package net.badgersmc.em.interaction.gui

import com.github.stefvanschie.inventoryframework.adventuresupport.ComponentHolder
import com.github.stefvanschie.inventoryframework.gui.GuiItem
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui
import com.github.stefvanschie.inventoryframework.pane.StaticPane
import net.badgersmc.em.application.ShopVaultService
import net.badgersmc.nexus.i18n.LangService
import net.badgersmc.em.interaction.Menu
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/** Paginated GUI for the owner's barter vault (ItemShops parity SP3). */
class ShopVaultMenu(
    private val owner: Player,
    private val vaultService: ShopVaultService,
    private val page: Int,
    private val lang: LangService,
) : Menu {

    companion object {
        private const val PAGE_SIZE = 45
    }

    override fun open(player: Player) {
        val all = vaultService.contents(owner.uniqueId)
        val pages = if (all.isEmpty()) 1 else (all.size + PAGE_SIZE - 1) / PAGE_SIZE
        val p = page.coerceIn(1, pages)
        val slice = all.drop((p - 1) * PAGE_SIZE).take(PAGE_SIZE)

        val gui = ChestGui(6, ComponentHolder.of(lang.msg("gui.vault.title")))
        val pane = StaticPane(9, 6)
        gui.addPane(pane)

        for ((idx, itemAndAmt) in slice.withIndex()) {
            val (item, total) = itemAndAmt
            val slot = idx
            val icon = item.clone()
            val meta = icon.itemMeta ?: continue
            meta.lore(listOf(lang.msg("gui.vault.amount_lore", "amount" to total)))
            icon.itemMeta = meta
            pane.addItem(GuiItem(icon) { event ->
                event.isCancelled = true
                val shift = event.isShiftClick
                val requested = if (shift) total else item.maxStackSize.coerceAtMost(total)
                val removed = vaultService.withdraw(owner.uniqueId, item.clone().apply { amount = 1 }, requested)
                if (removed > 0) {
                    val give = item.clone().apply { amount = removed }
                    val leftover = player.inventory.addItem(give)
                    if (leftover.isNotEmpty()) {
                        // Re-deposit what didn't fit
                        val fit = removed - leftover.values.sumOf { it.amount }
                        if (fit > 0) {
                            player.sendMessage(lang.msg("gui.vault.full", "left" to (total - fit)))
                        }
                        vaultService.deposit(owner.uniqueId, item.clone().apply { amount = 1 }, leftover.values.sumOf { it.amount })
                    } else {
                        player.sendMessage(lang.msg("gui.vault.withdrew", "amount" to removed, "item" to item.type.name.lowercase()))
                    }
                }
                // Re-open to refresh
                ShopVaultMenu(owner, vaultService, p, lang).open(player)
            }, slot % 9, slot / 9)
        }

        // Bottom row: prev / page / next
        if (p > 1) {
            pane.addItem(GuiItem(ItemStack(Material.ARROW)) { event ->
                event.isCancelled = true
                ShopVaultMenu(owner, vaultService, p - 1, lang).open(player)
            }, 0, 5)
        }
        if (p < pages) {
            pane.addItem(GuiItem(ItemStack(Material.ARROW)) { event ->
                event.isCancelled = true
                ShopVaultMenu(owner, vaultService, p + 1, lang).open(player)
            }, 8, 5)
        }

        gui.show(player)
    }
}
