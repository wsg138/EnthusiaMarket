package net.badgersmc.em.interaction.bedrock

import net.badgersmc.em.application.ItemStackSerializer
import net.badgersmc.em.application.ShopFactory
import net.badgersmc.em.application.ShopSignRenderer
import net.badgersmc.em.domain.shop.ShopRepository
import net.badgersmc.em.domain.shop.SignDirection
import net.badgersmc.nexus.i18n.LangService
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Sign
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.geysermc.cumulus.form.CustomForm
import org.geysermc.cumulus.response.CustomFormResponse
import java.util.UUID
import java.util.logging.Logger

/**
 * Bedrock Cumulus CustomForm for creating a shop (REQ-012, TDD-60).
 *
 * Collects shop direction (SELL/BUY/TRADE), price/cost, and per-trade amount.
 * The sell item is the player's main-hand item, captured by the listener and
 * passed in as a base64-serialised ItemStack ([sellItemBase64]).
 */
@Suppress("LongParameterList")
class BedrockCreateShopForm(
    player: Player,
    private val stallOwner: UUID,
    private val stallId: String,
    private val signLoc: Location,
    private val containerLoc: Location,
    private val sellItemBase64: String,
    private val shopRepository: ShopRepository,
    logger: Logger,
    lang: LangService,
    private val signRenderer: ShopSignRenderer,
) : BedrockMenuBase(player, logger, lang) {

    override fun buildForm(): CustomForm {
        return CustomForm.builder()
            .title("Create Shop")
            .label("Set your shop's direction, price, and amount.")
            .dropdown("Shop direction", listOf("Sell", "Buy", "Trade"))
            .input("Price per trade", "e.g. 100", "100")
            .input("Amount per trade", "e.g. 1", "1")
            .validResultHandler { handleCreate(it) }
            .build()
    }

    @Suppress("ReturnCount", "ComplexCondition", "ThrowsCount")
    private fun handleCreate(response: CustomFormResponse) {
        val direction = directionFrom(response.asDropdown(1) ?: 0)
        val priceText = response.asInput(2) ?: ""
        val amountText = response.asInput(3) ?: "1"
        val amount = amountText.toIntOrNull() ?: 1
        if (amount <= 0) {
            player.sendMessage(lang.legacy("shop.create.invalid_input"))
            return
        }
        val costItemBase64: String?
        val costAmount: Int
        val price: Long
        if (direction == SignDirection.TRADE) {
            val trade = parseTradeCost(priceText)
            if (trade == null) {
                player.sendMessage(lang.legacy("shop.create.invalid_trade_cost"))
                return
            }
            price = 0
            costAmount = trade.first
            costItemBase64 = trade.second
        } else {
            val p = priceText.toLongOrNull()
            if (p == null || p <= 0) {
                player.sendMessage(lang.legacy("shop.create.invalid_input"))
                return
            }
            price = p
            costAmount = 0
            costItemBase64 = null
        }
        val shop = ShopFactory.build(
            stallId = stallId, owner = stallOwner,
            signWorld = signLoc.world?.name ?: "world",
            signX = signLoc.blockX, signY = signLoc.blockY, signZ = signLoc.blockZ,
            containerWorld = containerLoc.world?.name ?: "world",
            containerX = containerLoc.blockX, containerY = containerLoc.blockY, containerZ = containerLoc.blockZ,
            sellItemBase64 = sellItemBase64, sellAmount = amount, price = price,
            direction = direction,
            costItemBase64 = costItemBase64, costAmountOverride = costAmount,
            searchEnabled = true,
        )
        shopRepository.upsert(shop)
        renderSign(shop)
        player.sendMessage(lang.legacy("shop.create.success"))
    }

    /** Parse "16 diamond" → Pair(16, base64). Returns null on failure. */
    private fun parseTradeCost(text: String): Pair<Int, String>? {
        val parts = text.split("\\s+".toRegex(), limit = 2)
        val parsedCost = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val material = runCatching { Material.valueOf(parts.getOrNull(1)?.uppercase() ?: "") }.getOrNull()
        if (parsedCost <= 0 || material == null || !material.isItem) return null
        return Pair(parsedCost, ItemStackSerializer.serialize(ItemStack(material, 1)))
    }

    private fun directionFrom(idx: Int): SignDirection = when (idx) {
        1 -> SignDirection.BUY
        2 -> SignDirection.TRADE
        else -> SignDirection.SELL
    }

    /** Write the shop's sign text via [ShopSignRenderer], matching SignPlaceListener. */
    private fun renderSign(shop: net.badgersmc.em.domain.shop.Shop) {
        val state = signLoc.block.state as? Sign ?: return
        val deserialized = ItemStackSerializer.deserialize(shop.sellItem)
        val sell = deserialized?.type?.name?.lowercase() ?: "?"
        val displayName = deserialized?.itemMeta?.displayName()
        val costDisplay = if (shop.direction == SignDirection.TRADE) {
            val costMat = ItemStackSerializer.deserialize(shop.costItem)?.type?.name?.lowercase() ?: "?"
            "${shop.costAmount}x $costMat"
        } else {
            "${shop.costAmount}"
        }
        val side = state.getSide(org.bukkit.block.sign.Side.FRONT)
        signRenderer.lines(shop.direction, sell, shop.sellAmount, costDisplay, displayName)
            .forEachIndexed { i, c -> side.line(i, c) }
        state.update(true, false)
    }
}
