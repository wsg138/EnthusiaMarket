package net.badgersmc.em.interaction.gui

import com.github.stefvanschie.inventoryframework.adventuresupport.ComponentHolder
import com.github.stefvanschie.inventoryframework.gui.GuiItem
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui
import net.badgersmc.em.interaction.blockItemTheft
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
 * IFramework GUI for creating a sign shop (REQ-012, REQ-289).
 * Collects direction (SELL/BUY/TRADE), per-trade amount, and cost
 * (Vault currency or barter item) before persisting via [ShopFactory].
 */
class CreateShopMenu(
    private val stallId: String,
    private val stallOwner: UUID,
    private val signLoc: Location,
    private val containerLoc: Location,
    private val sellItemBase64: String,
    private val shopRepository: ShopRepository,
    private val lang: LangService,
    private val initialDirection: SignDirection = SignDirection.SELL,
    private val initialAmount: Int = 1,
    private val initialPrice: Long = 100,
    private val initialCostItemB64: String? = null,
    private val initialCostAmount: Int? = null,
    private val guildId: String? = null,
) : Menu {

    private var direction: SignDirection = initialDirection
    private var price: Long = initialPrice
    private var amount: Int = initialAmount
    // Barter-mode cost (TRADE shops use an item, not Vault currency)
    private var costItemB64: String? = initialCostItemB64
    private var costItemAmount: Int = initialCostAmount ?: 1

    override fun open(player: Player) {
        render(player)
    }

    @Suppress("LongMethod")
    private fun render(player: Player) {
        val gui = ChestGui(4, ComponentHolder.of(lang.msg("gui.shop.create.title")))
        val pane = StaticPane(9, 4)

        // Row 0: direction selector
        val dirColors = mapOf(
            SignDirection.SELL to Material.LIME_STAINED_GLASS_PANE,
            SignDirection.BUY to Material.GOLD_BLOCK,
            SignDirection.TRADE to Material.PURPLE_STAINED_GLASS_PANE,
        )
        SignDirection.entries.forEachIndexed { idx, dir ->
            val mat = dirColors[dir] ?: Material.WHITE_STAINED_GLASS_PANE
            val sel = if (dir == direction) " \u2714" else ""
            pane.addItem(GuiItem(decorated(mat, lang.msg("gui.shop.create.dir_${dir.name.lowercase()}", "sel" to sel))) { event ->
                event.isCancelled = true
                direction = dir
                // Clear barter item when switching away from TRADE (CR#3)
                if (dir != SignDirection.TRADE) costItemB64 = null
                render(player)
            }, 1 + idx * 3, 0)
        }

        // Row 1: sell item preview + amount controls
        val preview = ItemStackSerializer.deserialize(sellItemBase64) ?: ItemStack(Material.BARRIER)
        pane.addItem(GuiItem(preview), 2, 1)
        pane.addItem(GuiItem(decorated(Material.LIME_DYE, lang.msg("gui.shop.create.amount_up", "amount" to amount))) { event ->
            event.isCancelled = true; amount += 1; render(player)
        }, 4, 1)
        pane.addItem(GuiItem(decorated(Material.PAPER, lang.msg("gui.shop.create.amount", "amount" to amount))), 5, 1)
        pane.addItem(GuiItem(decorated(Material.RED_DYE, lang.msg("gui.shop.create.amount_down", "amount" to amount))) { event ->
            event.isCancelled = true; amount = (amount - 1).coerceAtLeast(1); render(player)
        }, 6, 1)

        // Row 2: cost configuration
        if (direction == SignDirection.TRADE) {
            renderBarterCost(pane, player)
        } else {
            renderCurrencyCost(pane)
        }

        // Row 3: confirm + cancel
        pane.addItem(GuiItem(decorated(Material.LIME_STAINED_GLASS_PANE, lang.msg("gui.shop.create.confirm"))) { event ->
            event.isCancelled = true
            val shop = ShopFactory.build(
                stallId = stallId, owner = stallOwner, creator = player.uniqueId,
                signWorld = signLoc.world?.name ?: "world",
                signX = signLoc.blockX, signY = signLoc.blockY, signZ = signLoc.blockZ,
                containerWorld = containerLoc.world?.name ?: "world",
                containerX = containerLoc.blockX, containerY = containerLoc.blockY, containerZ = containerLoc.blockZ,
                sellItemBase64 = sellItemBase64, sellAmount = amount, price = price,
                direction = direction,
                searchEnabled = true,
                costItemBase64 = costItemB64,
                costAmountOverride = if (direction == SignDirection.TRADE) costItemAmount else null,
                guildId = guildId,
            )
            shopRepository.upsert(shop)
            player.closeInventory()
            player.sendMessage(lang.msg("shop.create.success"))
        }, 7, 3)
        pane.addItem(GuiItem(decorated(Material.RED_CONCRETE, lang.msg("gui.shop.create.cancel"))) { event ->
            event.isCancelled = true; player.closeInventory()
        }, 1, 3)

        gui.addPane(pane)
        gui.blockItemTheft()
        gui.show(player)
    }

    private fun renderCurrencyCost(pane: StaticPane) {
        pane.addItem(GuiItem(decorated(Material.LIME_DYE, lang.msg("gui.shop.create.price_up", "price" to price))) {
            it.isCancelled = true; price += 10; (it.whoClicked as? Player)?.let { p -> render(p) }
        }, 2, 2)
        pane.addItem(GuiItem(decorated(Material.EMERALD, lang.msg("gui.shop.create.price", "price" to price))), 5, 2)
        pane.addItem(GuiItem(decorated(Material.RED_DYE, lang.msg("gui.shop.create.price_down", "price" to price))) {
            it.isCancelled = true; price = (price - 10).coerceAtLeast(1); (it.whoClicked as? Player)?.let { p -> render(p) }
        }, 8, 2)
    }

    private fun renderBarterCost(pane: StaticPane, player: Player) {
        // Cost item from hand button
        pane.addItem(GuiItem(decorated(Material.CHEST, lang.msg("gui.shop.create.cost_item_set"))) { event ->
            event.isCancelled = true
            val hand = player.inventory.itemInMainHand
            if (hand.type != Material.AIR && hand.amount > 0) {
                costItemB64 = ItemStackSerializer.serialize(hand.clone().apply { amount = 1 })
                costItemAmount = hand.amount.coerceAtLeast(1)
                render(player)
            }
        }, 2, 2)

        // Show current cost item
        val costPreview = costItemB64?.let { ItemStackSerializer.deserialize(it) } ?: ItemStack(Material.EMERALD)
        pane.addItem(GuiItem(costPreview), 4, 2)

        // Cost amount controls
        pane.addItem(GuiItem(decorated(Material.LIME_DYE, lang.msg("gui.shop.create.cost_amount_up", "amount" to costItemAmount))) { event ->
            event.isCancelled = true; costItemAmount += 1; render(player)
        }, 6, 2)
        pane.addItem(GuiItem(decorated(Material.PAPER, lang.msg("gui.shop.create.cost_amount", "amount" to costItemAmount))), 7, 2)
        pane.addItem(GuiItem(decorated(Material.RED_DYE, lang.msg("gui.shop.create.cost_amount_down", "amount" to costItemAmount))) { event ->
            event.isCancelled = true; costItemAmount = (costItemAmount - 1).coerceAtLeast(1); render(player)
        }, 8, 2)
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
