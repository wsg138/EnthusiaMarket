package net.badgersmc.em.infrastructure.listeners

import net.badgersmc.em.application.ContainerTradeService
import net.badgersmc.em.domain.shop.Shop
import net.badgersmc.em.domain.shop.ShopRepository
import net.badgersmc.nexus.i18n.LangService
import net.badgersmc.em.interaction.MenuFactory
import net.badgersmc.em.interaction.gui.PurchaseMenu
import net.badgersmc.nexus.annotations.Component
import net.badgersmc.nexus.annotations.PostConstruct
import org.bukkit.Bukkit
import org.bukkit.block.Sign
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.plugin.java.JavaPlugin

/**
 * Listens for right-click on registered shop signs and opens the PurchaseMenu (REQ-013).
 */
@Component
open class ShopInteractListener(
    private val shopRepository: ShopRepository,
    private val menuFactory: MenuFactory,
    private val tradeService: ContainerTradeService,
    private val lang: LangService
) : Listener {

    @PostConstruct
    fun register() {
        val plugin = Bukkit.getPluginManager().getPlugin("EnthusiaMarket") as? JavaPlugin ?: return
        Bukkit.getPluginManager().registerEvents(this, plugin)
    }

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

        // Platform routing: Bedrock gets Cumulus form, Java gets IFramework
        if (menuFactory.shouldUseBedrockMenus(player)) {
            // TODO: Implement Bedrock form menu (TDD-60) — placeholder path
            player.sendMessage(lang.msg("shop.create.bedrock_placeholder"))
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
}