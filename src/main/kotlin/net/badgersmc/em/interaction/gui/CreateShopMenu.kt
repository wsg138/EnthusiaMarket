package net.badgersmc.em.interaction.gui

import com.github.stefvanschie.inventoryframework.adventuresupport.ComponentHolder
import com.github.stefvanschie.inventoryframework.gui.GuiItem
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui
import com.github.stefvanschie.inventoryframework.pane.StaticPane
import net.badgersmc.em.application.ItemStackSerializer
import net.badgersmc.em.application.ShopFactory
import net.badgersmc.em.domain.shop.ShopRepository
import net.badgersmc.em.domain.shop.SignDirection
import net.badgersmc.nexus.i18n.LangService
import net.badgersmc.em.interaction.Menu
import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.UUID

/**
 * Java IFramework GUI for creating a sign shop (REQ-012, TDD-52). The sell
 * item is the player's main-hand item (captured by the listener, passed as
 * base64). Price + per-trade amount are adjusted with +/- buttons; confirm
 * builds the Shop via [ShopFactory] and persists it.
 */
class CreateShopMenu(
    private val stallId: String,
    private val stallOwner: UUID,
    private val signLoc: Location,
    private val containerLoc: Location,
    private val sellItemBase64: String,
    private val shopRepository: ShopRepository,
    private val lang: LangService,
) : Menu {

    private var price: Long = 100
    private var amount: Int = 1

    override fun open(player: Player) {
        render(player)
    }

    private fun render(player: Player) {
        val gui = ChestGui(3, ComponentHolder.of(lang.msg("gui.shop.create.title")))
        val pane = StaticPane(9, 3)

        // Sell item preview (decoded from base64).
        val preview = ItemStackSerializer.deserialize(sellItemBase64) ?: ItemStack(Material.BARRIER)
        pane.addItem(GuiItem(preview), 2, 1)

        // Price controls.
        pane.addItem(GuiItem(decorated(Material.LIME_DYE, lang.msg("gui.shop.create.price_up", "price" to price))) { event ->
            event.isCancelled = true; price += 10; render(player)
        }, 4, 0)
        pane.addItem(GuiItem(decorated(Material.EMERALD, lang.msg("gui.shop.create.price", "price" to price))), 4, 1)
        pane.addItem(GuiItem(decorated(Material.RED_DYE, lang.msg("gui.shop.create.price_down", "price" to price))) { event ->
            event.isCancelled = true; price = (price - 10).coerceAtLeast(1); render(player)
        }, 4, 2)

        // Amount controls.
        pane.addItem(GuiItem(decorated(Material.LIME_DYE, lang.msg("gui.shop.create.amount_up", "amount" to amount))) { event ->
            event.isCancelled = true; amount += 1; render(player)
        }, 6, 0)
        pane.addItem(GuiItem(decorated(Material.PAPER, lang.msg("gui.shop.create.amount", "amount" to amount))), 6, 1)
        pane.addItem(GuiItem(decorated(Material.RED_DYE, lang.msg("gui.shop.create.amount_down", "amount" to amount))) { event ->
            event.isCancelled = true; amount = (amount - 1).coerceAtLeast(1); render(player)
        }, 6, 2)

        // Confirm.
        pane.addItem(GuiItem(decorated(Material.LIME_STAINED_GLASS_PANE, lang.msg("gui.shop.create.confirm"))) { event ->
            event.isCancelled = true
            val shop = ShopFactory.build(
                stallId = stallId, owner = stallOwner, creator = player.uniqueId,
                signWorld = signLoc.world?.name ?: "world",
                signX = signLoc.blockX, signY = signLoc.blockY, signZ = signLoc.blockZ,
                containerWorld = containerLoc.world?.name ?: "world",
                containerX = containerLoc.blockX, containerY = containerLoc.blockY, containerZ = containerLoc.blockZ,
                sellItemBase64 = sellItemBase64, sellAmount = amount, price = price,
                direction = SignDirection.SELL,
                searchEnabled = true,
            )
            shopRepository.upsert(shop)
            player.closeInventory()
            player.sendMessage(lang.msg("shop.create.success"))
        }, 8, 2)

        gui.addPane(pane)
        gui.show(player)
    }

    private fun decorated(material: Material, name: Component, lore: List<Component> = emptyList()): ItemStack {
        val item = ItemStack(material)
        val meta = item.itemMeta ?: return item
        meta.displayName(name)
        if (lore.isNotEmpty()) meta.lore(lore)
        item.itemMeta = meta
        return item
    }
}