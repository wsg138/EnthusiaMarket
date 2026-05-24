package net.badgersmc.em.interaction.gui

import com.github.stefvanschie.inventoryframework.gui.GuiItem
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui
import com.github.stefvanschie.inventoryframework.pane.StaticPane
import net.badgersmc.em.domain.shop.Shop
import net.badgersmc.em.domain.shop.ShopRepository
import net.badgersmc.em.interaction.Menu
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * IFramework GUI for editing a shop's settings (REQ-023).
 * Owner can freeze/unfreeze, configure hopper, and manage trust.
 */
class ShopEditMenu(
    private val player: Player,
    private val shop: Shop,
    private val shopRepository: ShopRepository
) : Menu {

    override fun open() {
        val gui = ChestGui(3, "§8Edit Shop")
        val pane = StaticPane(9, 3)
        
        // Slot 11: Freeze toggle
        val freezeStack = if (shop.frozen) {
            ItemStack(Material.RED_STAINED_GLASS_PANE).apply {
                itemMeta = itemMeta?.apply {
                    setDisplayName("§c§lSHOP FROZEN")
                    lore = listOf("§7Trades are currently BLOCKED", "", "§eClick to unfreeze")
                }
            }
        } else {
            ItemStack(Material.LIME_STAINED_GLASS_PANE).apply {
                itemMeta = itemMeta?.apply {
                    setDisplayName("§a§lSHOP ACTIVE")
                    lore = listOf("§7Trades are currently allowed", "", "§eClick to freeze")
                }
            }
        }
        pane.addItem(GuiItem(freezeStack, { event ->
            event.isCancelled = true
            val updated = shop.copy(frozen = !shop.frozen)
            shopRepository.upsert(updated)
            player.sendMessage(if (updated.frozen) "§cShop frozen" else "§aShop unfrozen")
            ShopEditMenu(player, updated, shopRepository).open()
        }), 2, 1)
        
        // Slot 13: Hopper In toggle
        val hopperInStack = if (shop.hopperAllowIn) {
            ItemStack(Material.HOPPER).apply {
                itemMeta = itemMeta?.apply {
                    setDisplayName("§aHopper In: ON")
                    lore = listOf("§7Hoppers can insert items", "§eClick to disable")
                }
            }
        } else {
            ItemStack(Material.HOPPER).apply {
                itemMeta = itemMeta?.apply {
                    setDisplayName("§cHopper In: OFF")
                    lore = listOf("§7Hoppers cannot insert items", "§eClick to enable")
                }
            }
        }
        pane.addItem(GuiItem(hopperInStack, { event ->
            event.isCancelled = true
            val updated = shop.copy(hopperAllowIn = !shop.hopperAllowIn)
            shopRepository.upsert(updated)
            ShopEditMenu(player, updated, shopRepository).open()
        }), 4, 1)
        
        // Slot 15: Hopper Out toggle
        val hopperOutStack = if (shop.hopperAllowOut) {
            ItemStack(Material.HOPPER_MINECART).apply {
                itemMeta = itemMeta?.apply {
                    setDisplayName("§aHopper Out: ON")
                    lore = listOf("§7Hoppers can extract items", "§eClick to disable")
                }
            }
        } else {
            ItemStack(Material.HOPPER_MINECART).apply {
                itemMeta = itemMeta?.apply {
                    setDisplayName("§cHopper Out: OFF")
                    lore = listOf("§7Hoppers cannot extract items", "§eClick to enable")
                }
            }
        }
        pane.addItem(GuiItem(hopperOutStack, { event ->
            event.isCancelled = true
            val updated = shop.copy(hopperAllowOut = !shop.hopperAllowOut)
            shopRepository.upsert(updated)
            ShopEditMenu(player, updated, shopRepository).open()
        }), 6, 1)
        
        // Slot 22: Trust management
        val trustStack = ItemStack(Material.PLAYER_HEAD).apply {
            itemMeta = itemMeta?.apply {
                setDisplayName("§eManage Trusted Players")
                lore = listOf("§7Currently trusted: ${shop.trusted.size}", "§eClick to open")
            }
        }
        pane.addItem(GuiItem(trustStack, { event ->
            event.isCancelled = true
            TrustManageMenu(player, shop, shopRepository).open()
        }), 4, 2)
        
        gui.addPane(pane)
        gui.show(player)
    }
}
