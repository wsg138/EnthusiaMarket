package net.badgersmc.em.interaction.gui

import com.github.stefvanschie.inventoryframework.adventuresupport.ComponentHolder
import com.github.stefvanschie.inventoryframework.gui.GuiItem
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui
import net.badgersmc.em.interaction.blockItemTheft
import com.github.stefvanschie.inventoryframework.pane.StaticPane
import net.badgersmc.em.application.ShopVaultService
import net.badgersmc.nexus.i18n.LangService
import net.badgersmc.em.interaction.Menu
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.UUID

/**
 * Read-only admin view of another player's barter vault (IS2-9, REQ-295).
 * Paginated 45 items/page, no withdraw/click actions.
 */
class VaultAdminMenu(
    private val target: UUID,
    private val targetName: String,
    private val vaultService: ShopVaultService,
    private val lang: LangService,
    private val page: Int = 1,
) : Menu {

    companion object {
        private const val PAGE_SIZE = 45
    }

    override fun open(player: Player) {
        val all = vaultService.contents(target)
        val pages = if (all.isEmpty()) 1 else (all.size + PAGE_SIZE - 1) / PAGE_SIZE
        val p = page.coerceIn(1, pages)
        val slice = all.drop((p - 1) * PAGE_SIZE).take(PAGE_SIZE)

        val gui = ChestGui(6, ComponentHolder.of(lang.msg("gui.vault_admin.title", "name" to targetName)))
        val pane = StaticPane(9, 6)
        gui.addPane(pane)

        if (slice.isEmpty()) {
            gui.blockItemTheft()
            gui.show(player)
            return
        }

        for ((idx, itemAndAmt) in slice.withIndex()) {
            val (item, total) = itemAndAmt
            val icon = item.clone()
            val meta = icon.itemMeta ?: continue
            meta.lore(listOf(lang.msg("gui.vault_admin.amount_lore", "amount" to total)))
            icon.itemMeta = meta
            pane.addItem(GuiItem(icon) { it.isCancelled = true }, idx % 9, idx / 9)
        }

        // Navigation
        if (p > 1) {
            pane.addItem(GuiItem(ItemStack(Material.ARROW)) { event ->
                event.isCancelled = true
                VaultAdminMenu(target, targetName, vaultService, lang, p - 1).open(player)
            }, 0, 5)
        }
        if (p < pages) {
            pane.addItem(GuiItem(ItemStack(Material.ARROW)) { event ->
                event.isCancelled = true
                VaultAdminMenu(target, targetName, vaultService, lang, p + 1).open(player)
            }, 8, 5)
        }

        gui.blockItemTheft()
        gui.show(player)
    }
}