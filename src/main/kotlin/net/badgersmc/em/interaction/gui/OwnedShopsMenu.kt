package net.badgersmc.em.interaction.gui

import com.github.stefvanschie.inventoryframework.adventuresupport.ComponentHolder
import com.github.stefvanschie.inventoryframework.gui.GuiItem
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui
import net.badgersmc.em.interaction.blockItemTheft
import com.github.stefvanschie.inventoryframework.pane.StaticPane
import net.badgersmc.em.application.ItemStackSerializer
import net.badgersmc.em.application.ShopManagementService
import net.badgersmc.em.domain.shop.ShopRepository
import net.badgersmc.nexus.i18n.LangService
import net.badgersmc.em.interaction.Menu
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.UUID

/** Paginated (first 45) list of the owner's shops; click to open the edit menu. */
class OwnedShopsMenu(
    private val owner: UUID,
    private val shopRepository: ShopRepository,
    private val management: ShopManagementService,
    private val lang: LangService,
) : Menu {
    override fun open(player: Player) {
        val shops = management.shopsOwnedBy(owner).take(45)
        val gui = ChestGui(6, ComponentHolder.of(lang.msg("gui.shop.owned.title")))
        val pane = StaticPane(9, 6)
        shops.forEachIndexed { idx, shop ->
            val base = ItemStackSerializer.deserialize(shop.sellItem) ?: ItemStack(Material.CHEST)
            val meta = base.itemMeta
            if (meta != null) {
                meta.displayName(lang.msg("gui.shop.owned.icon", "sell_amt" to shop.sellAmount, "cost" to shop.costAmount, "world" to shop.signWorld, "x" to shop.signX, "y" to shop.signY, "z" to shop.signZ))
                base.itemMeta = meta
            }
            pane.addItem(GuiItem(base) {
                it.isCancelled = true
                ShopEditMenu(shop, shopRepository, management, lang).open(player)
            }, idx % 9, idx / 9)
        }
        gui.addPane(pane)
        gui.blockItemTheft()
        gui.show(player)
    }
}