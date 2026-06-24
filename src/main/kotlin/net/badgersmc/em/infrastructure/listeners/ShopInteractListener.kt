package net.badgersmc.em.infrastructure.listeners

import net.badgersmc.em.application.ContainerTradeService
import net.badgersmc.em.application.ItemStackSerializer
import net.badgersmc.em.domain.shop.Shop
import net.badgersmc.em.domain.shop.ShopRepository
import net.badgersmc.em.interaction.ShopInfoCard
import net.badgersmc.nexus.i18n.LangService
import net.badgersmc.em.interaction.MenuFactory
import net.badgersmc.em.interaction.gui.PurchaseMenu
import net.badgersmc.nexus.annotations.Component
import org.bukkit.Bukkit
import org.bukkit.block.Sign
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot

/**
 * Listens for right-click on registered shop signs and opens the PurchaseMenu (REQ-013).
 */
@net.badgersmc.nexus.paper.listeners.Listener
@Component
open class ShopInteractListener(
    private val shopRepository: ShopRepository,
    private val menuFactory: MenuFactory,
    private val tradeService: ContainerTradeService,
    private val lang: LangService,
) : Listener {

    private val logger = java.util.logging.Logger.getLogger(ShopInteractListener::class.java.name)

    @EventHandler
    fun onSignRightClick(event: PlayerInteractEvent) {
        // Only right-click on blocks with main hand
        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        if (event.hand != EquipmentSlot.HAND) return

        val block = event.clickedBlock ?: return
        if (block.state !is Sign) return

        val loc = block.location
        val shop = shopRepository.findBySign(
            loc.world?.name ?: "world", loc.blockX, loc.blockY, loc.blockZ
        ) ?: return

        event.isCancelled = true

        val player = event.player

        // Shift-right-click → info card (ItemShops parity SP6)
        if (player.isSneaking) {
            val owner = org.bukkit.Bukkit.getOfflinePlayer(shop.owner).name ?: "Unknown"
            val item = ItemStackSerializer.deserialize(shop.sellItem)?.type?.name?.lowercase() ?: "?"
            ShopInfoCard.lines(
                lang, shop.direction.name, item, shop.sellAmount, shop.costAmount.toLong(), owner, stockOf(shop),
            ).forEach { player.sendMessage(it) }
            return
        }

        // Platform routing: Bedrock gets Cumulus form, Java gets IFramework
        if (menuFactory.shouldUseBedrockMenus(player)) {
            openBedrockPurchaseForm(player, shop)
        } else {
            openPurchaseMenu(player, shop)
        }
    }

    /**
     * Open the PurchaseMenu for the given player and shop.
     * Open for testability — override or spy in tests to verify invocation.
     */
    open fun openPurchaseMenu(player: Player, shop: Shop) {
        PurchaseMenu(shop, tradeService, lang).open(player)
    }

    /** Open the Bedrock purchase form with trade callbacks. Open for testability. */
    open fun openBedrockPurchaseForm(player: Player, shop: Shop) {
        val uuid = player.uniqueId
        net.badgersmc.em.interaction.bedrock.BedrockPurchaseForm(
            player, shop,
            onBuy = { tradeService.executeBuy(shop, uuid) },
            onSell = { tradeService.executeSell(shop, uuid) },
            logger, lang,
        ).open(player)
    }

    private fun stockOf(shop: Shop): Int {
        val world = Bukkit.getWorld(shop.containerWorld) ?: return 0
        val state = world.getBlockAt(shop.containerX, shop.containerY, shop.containerZ).state
        val inv = (state as? org.bukkit.block.Container)?.inventory ?: return 0
        val sellStack = ItemStackSerializer.deserialize(shop.sellItem) ?: return 0
        val total = inv.contents.filterNotNull().filter { it.isSimilar(sellStack) }.sumOf { it.amount }
        return total / shop.sellAmount.coerceAtLeast(1)
    }
}
