package net.badgersmc.em.infrastructure.listeners

import net.badgersmc.em.application.ContainerTradeService
import net.badgersmc.em.application.ItemStackSerializer
import net.badgersmc.em.domain.shop.Shop
import net.badgersmc.em.domain.shop.SignDirection
import net.badgersmc.em.domain.shop.ShopRepository
import net.badgersmc.em.interaction.ShopInfoCard
import net.badgersmc.nexus.i18n.LangService
import net.badgersmc.em.interaction.MenuFactory
import net.badgersmc.em.interaction.gui.PurchaseMenu
import net.badgersmc.nexus.annotations.Component
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.block.Sign
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.util.Vector

/**
 * Listens for player interaction with registered shop signs and opens the PurchaseMenu (REQ-013).
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
    fun onSignInteract(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK) return
        if (event.hand != EquipmentSlot.HAND) return

        val block = event.clickedBlock ?: return
        if (block.state !is Sign) return
        val shop = findShopAtSign(block) ?: return

        // Right-click: open the shop. Cancel the event and deny item use
        // to suppress held-item abilities (e.g. spear lunge).
        // Exception: let Mace users keep their weapon behavior (REQ-019).
        val held = event.player.inventory.itemInMainHand
        if (held.type == org.bukkit.Material.MACE) return

        event.isCancelled = true
        event.setUseItemInHand(org.bukkit.event.Event.Result.DENY)
        openShop(event.player, shop)
    }

    /**
     * Spear lunge bypass: vanilla 1.21+ spears extend reach, so a charged left-click
     * fires [Action.LEFT_CLICK_AIR] rather than [Action.LEFT_CLICK_BLOCK].
     * Raycasts from the player's eye to find a targeted shop sign and open it.
     */
    @EventHandler
    fun onSpearLunge(event: PlayerInteractEvent) {
        if (event.action != Action.LEFT_CLICK_AIR) return
        if (event.hand != EquipmentSlot.HAND) return

        val player = event.player
        val held = player.inventory.itemInMainHand
        if (!isSpear(held.type)) return

        // Shift + spear lunge = info card
        val targetBlock = raycastSign(player) ?: return
        if (targetBlock.state !is Sign) return
        val shop = findShopAtSign(targetBlock) ?: return

        event.isCancelled = true
        event.setUseItemInHand(org.bukkit.event.Event.Result.DENY)
        openShop(player, shop)
    }

    // ---- helpers ----

    private fun isSpear(mat: Material): Boolean = mat.name.endsWith("_SPEAR")

    /**
     * Raycast from the player's eye position forward, checking each 0.25-block
     * step for a solid block. Returns the first solid block hit. Max range: 6 blocks
     * (covers the spear's extended reach comfortably).
     */
    private fun raycastSign(player: Player): Block? {
        val eye = player.eyeLocation
        val dir = eye.direction
        val step = dir.clone().multiply(0.25)
        var current = eye.clone()
        var steps = 24 // 24 * 0.25 = 6 blocks
        while (steps-- > 0) {
            current = current.add(step)
            val block = current.block
            val type = block.type
            if (type == Material.AIR || type == Material.CAVE_AIR || type == Material.VOID_AIR) continue
            if (type.isOccluding || block.state is Sign) return block
            if (type.isSolid) break
        }
        return null
    }

    private fun findShopAtSign(block: Block): Shop? {
        val loc = block.location
        return shopRepository.findBySign(
            loc.world?.name ?: "world", loc.blockX, loc.blockY, loc.blockZ
        )
    }

    private fun openShop(player: Player, shop: Shop) {
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

    open fun openPurchaseMenu(player: Player, shop: Shop) {
        PurchaseMenu(shop, tradeService, lang).open(player)
    }

    open fun openBedrockPurchaseForm(player: Player, shop: Shop) {
        val uuid = player.uniqueId
        net.badgersmc.em.interaction.bedrock.BedrockPurchaseForm(
            player, shop,
            onConfirm = { executeBedrockTransaction(shop, uuid) },
            logger, lang,
        ).open(player)
    }

    internal fun executeBedrockTransaction(shop: Shop, playerUuid: java.util.UUID) = when (shop.direction) {
        SignDirection.SELL -> tradeService.executeSell(shop, playerUuid)
        SignDirection.BUY -> tradeService.executeBuy(shop, playerUuid)
        SignDirection.TRADE -> tradeService.executeTrade(shop, playerUuid)
    }

    private fun stockOf(shop: Shop): Int {
        val world = Bukkit.getWorld(shop.containerWorld) ?: return 0
        val state = world.getBlockAt(shop.containerX, shop.containerY, shop.containerZ).state
        val inv = (state as? org.bukkit.block.Container)?.inventory ?: return 0
        val sellStack = ItemStackSerializer.deserialize(shop.sellItem) ?: return 0
        val total = net.badgersmc.em.application.ItemStackMatch.countSimilar(inv, sellStack)
        return total / shop.sellAmount.coerceAtLeast(1)
    }
}
