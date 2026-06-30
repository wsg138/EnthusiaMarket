package net.badgersmc.em.interaction.gui

import com.github.stefvanschie.inventoryframework.adventuresupport.ComponentHolder
import com.github.stefvanschie.inventoryframework.gui.GuiItem
import com.github.stefvanschie.inventoryframework.gui.type.ChestGui
import com.github.stefvanschie.inventoryframework.pane.StaticPane
import net.badgersmc.em.application.ItemStackSerializer
import net.badgersmc.em.application.ShopFactory
import net.badgersmc.em.domain.shop.Shop
import net.badgersmc.em.domain.shop.ShopRepository
import net.badgersmc.em.domain.shop.SignDirection
import net.badgersmc.em.interaction.Menu
import net.badgersmc.em.interaction.blockItemTheft
import net.badgersmc.nexus.i18n.LangService
import net.kyori.adventure.text.Component
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Sign
import org.bukkit.block.sign.Side
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.util.UUID

/**
 * IFramework GUI for creating a sign shop (REQ-012, REQ-289).
 * Collects direction (SELL/BUY/TRADE), per-trade amount, and cost
 * (Vault currency or barter item) before persisting via [ShopFactory].
 *
 * Step buttons: +1, +5, +10, -10, -5, -1 for both trade amount and price/cost.
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
                if (dir != SignDirection.TRADE) costItemB64 = null
                render(player)
            }, 1 + idx * 3, 0)
        }

        // Row 1: sell item preview + amount controls
        // Layout: [preview] [+1][+5][+10][AMOUNT][-10][-5][-1]
        val preview = ItemStackSerializer.deserialize(sellItemBase64) ?: ItemStack(Material.BARRIER)
        pane.addItem(GuiItem(preview), 0, 1)
        addStepButtons(pane, 1, 1,
            get = { amount.toLong() },
            set = { amount = it.toInt() },
            rerender = { render(player) },
            coerce = { coerceAtLeast(1L) },
            displayLabel = { lang.msg("gui.shop.create.amount", "amount" to amount) },
        )

        // Row 2: cost configuration
        if (direction == SignDirection.TRADE) {
            renderBarterCost(pane, player)
        } else {
            renderCurrencyCost(pane, player)
        }

        // Row 3: confirm + cancel
        pane.addItem(GuiItem(decorated(Material.LIME_STAINED_GLASS_PANE, lang.msg("gui.shop.create.confirm"))) { event ->
            event.isCancelled = true
            val shop = ShopFactory.build(
                stallId = stallId, owner = stallOwner,
                signWorld = signLoc.world?.name ?: "world",
                signX = signLoc.blockX, signY = signLoc.blockY, signZ = signLoc.blockZ,
                containerWorld = containerLoc.world?.name ?: "world",
                containerX = containerLoc.blockX, containerY = containerLoc.blockY, containerZ = containerLoc.blockZ,
                sellItemBase64 = sellItemBase64, sellAmount = amount, price = price,
                direction = direction,
                searchEnabled = true,
                costItemBase64 = costItemB64,
                costAmountOverride = if (direction == SignDirection.TRADE) costItemAmount else null,
            )
            if (!writeSignText(shop)) {
                player.closeInventory()
                player.sendMessage(lang.msg("shop.create.sign_failed"))
                return@GuiItem
            }
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

    /**
     * Add 6 step buttons + center display in a row:
     *   [+1][+5][+10]  [VALUE]  [-10][-5][-1]
     */
    @Suppress("LongParameterList")
    private fun addStepButtons(
        pane: StaticPane,
        col: Int,
        row: Int,
        get: () -> Long,
        set: (Long) -> Unit,
        rerender: () -> Unit,
        coerce: Long.() -> Long,
        displayLabel: () -> Component,
    ) {
        data class Step(val delta: Int, val mat: Material, val langKey: String)

        val steps = listOf(
            Step(1, Material.LIME_DYE, "gui.shop.create.btn_plus1"),
            Step(5, Material.LIME_STAINED_GLASS, "gui.shop.create.btn_plus5"),
            Step(10, Material.LIME_STAINED_GLASS_PANE, "gui.shop.create.btn_plus10"),
            // display at col+3
            Step(-10, Material.RED_STAINED_GLASS_PANE, "gui.shop.create.btn_minus10"),
            Step(-5, Material.RED_STAINED_GLASS, "gui.shop.create.btn_minus5"),
            Step(-1, Material.RED_DYE, "gui.shop.create.btn_minus1"),
        )

        // +1, +5, +10
        for (i in 0..2) {
            val s = steps[i]
            pane.addItem(GuiItem(decorated(s.mat, lang.msg(s.langKey, "delta" to s.delta, "val" to (get() + s.delta)))) { event ->
                event.isCancelled = true
                set((get() + s.delta).coerce())
                rerender()
            }, col + i, row)
        }

        // Value display
        pane.addItem(GuiItem(decorated(Material.PAPER, displayLabel())), col + 3, row)

        // -10, -5, -1
        for (i in 3..5) {
            val s = steps[i]
            pane.addItem(GuiItem(decorated(s.mat, lang.msg(s.langKey, "delta" to s.delta, "val" to (get() + s.delta)))) { event ->
                event.isCancelled = true
                set((get() + s.delta).coerce())
                rerender()
            }, col + 4 + (i - 3), row)
        }
    }

    private fun renderCurrencyCost(pane: StaticPane, player: Player) {
        addStepButtons(pane, 1, 2,
            get = { price.coerceIn(1, Long.MAX_VALUE) },
            set = { price = it },
            rerender = { render(player) },
            coerce = { coerceIn(1, Long.MAX_VALUE) },
            displayLabel = { lang.msg("gui.shop.create.price", "price" to price) },
        )
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
        }, 0, 2)

        // Show current cost item
        val costPreview = costItemB64?.let { ItemStackSerializer.deserialize(it) } ?: ItemStack(Material.EMERALD)
        pane.addItem(GuiItem(costPreview), 1, 2)

        // Cost amount controls with step buttons
        addStepButtons(pane, 2, 2,
            get = { costItemAmount.toLong() },
            set = { costItemAmount = it.toInt() },
            rerender = { render(player) },
            coerce = { coerceAtLeast(1L) },
            displayLabel = { lang.msg("gui.shop.create.cost_amount", "amount" to costItemAmount) },
        )
    }

    private fun decorated(material: Material, name: Component, lore: List<Component> = emptyList()): ItemStack {
        val item = ItemStack(material)
        val meta = item.itemMeta ?: return item
        meta.displayName(name)
        if (lore.isNotEmpty()) meta.lore(lore)
        item.itemMeta = meta
        return item
    }

    /** Write the shop's direction/amount/price onto the sign after creation. Returns true on success. */
    private fun writeSignText(shop: Shop): Boolean {
        val world = signLoc.world ?: return false
        val block = world.getBlockAt(signLoc.blockX, signLoc.blockY, signLoc.blockZ)
        val sign = block.state as? Sign ?: return false
        val side = sign.getSide(Side.FRONT)
        side.line(0, Component.text("[${shop.direction.name}]"))
        side.line(1, Component.text("${shop.sellAmount}"))
        val costText = if (shop.direction == SignDirection.TRADE) {
            val costItem = try { ItemStackSerializer.deserialize(shop.costItem) } catch (_: Exception) { null }
            "${shop.costAmount} ${costItem?.type?.name?.lowercase() ?: "?"}"
        } else {
            "${shop.costAmount}"
        }
        side.line(2, Component.text(costText))
        side.line(3, Component.text("[Shop]"))
        return sign.update(true, false)
    }
}
