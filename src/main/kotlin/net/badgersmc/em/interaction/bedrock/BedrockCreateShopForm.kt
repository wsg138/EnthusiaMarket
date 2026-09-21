package net.badgersmc.em.interaction.bedrock

import net.badgersmc.em.application.ItemStackSerializer
import net.badgersmc.em.application.ShopFactory
import net.badgersmc.em.application.ShopSignRenderer
import net.badgersmc.em.domain.shop.ShopRepository
import net.badgersmc.em.domain.shop.SignDirection
import net.badgersmc.em.events.ShopCreatedEvent
import net.badgersmc.nexus.i18n.LangService
import org.bukkit.Bukkit
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

    private fun handleCreate(response: CustomFormResponse) {
        val direction = directionFrom(response.asDropdown(1) ?: 0)
        val priceText = response.asInput(2) ?: ""
        val amountText = response.asInput(3) ?: "1"
        val amount = parseAmount(amountText) ?: return
        val pricing = parsePricing(direction, priceText) ?: return
        val shop = createShop(direction, amount, pricing)
        if (!renderSign(shop)) {
            player.sendMessage(lang.legacy("shop.create.sign_failed"))
            return
        }
        shopRepository.upsert(shop)
        player.sendMessage(lang.legacy("shop.create.success"))
        publishShopCreated(shop.owner)
    }

    private fun parseAmount(amountText: String): Int? {
        val amount = amountText.toIntOrNull() ?: 1
        if (amount > 0) {
            return amount
        }
        player.sendMessage(lang.legacy("shop.create.invalid_input"))
        return null
    }

    private fun parsePricing(direction: SignDirection, priceText: String): ShopFactory.Pricing? {
        return if (direction == SignDirection.TRADE) {
            parseTradePricing(priceText)
        } else {
            parseCurrencyPricing(priceText)
        }
    }

    private fun parseTradePricing(priceText: String): ShopFactory.Pricing? {
        val trade = parseTradeCost(priceText)
        if (trade == null) {
            player.sendMessage(lang.legacy("shop.create.invalid_trade_cost"))
            return null
        }
        return ShopFactory.Pricing(0, trade.second, trade.first)
    }

    private fun parseCurrencyPricing(priceText: String): ShopFactory.Pricing? {
        val price = priceText.toLongOrNull()
        if (price == null || price <= 0) {
            player.sendMessage(lang.legacy("shop.create.invalid_input"))
            return null
        }
        return ShopFactory.Pricing(price)
    }

    private fun createShop(
        direction: SignDirection,
        amount: Int,
        pricing: ShopFactory.Pricing,
    ): net.badgersmc.em.domain.shop.Shop {
        return ShopFactory.build(
            ShopFactory.BuildRequest(
                identity = ShopFactory.ShopIdentity(stallId, stallOwner),
                sign = ShopFactory.BlockPosition(
                    signLoc.world?.name ?: "world",
                    signLoc.blockX,
                    signLoc.blockY,
                    signLoc.blockZ,
                ),
                container = ShopFactory.BlockPosition(
                    containerLoc.world?.name ?: "world",
                    containerLoc.blockX,
                    containerLoc.blockY,
                    containerLoc.blockZ,
                ),
                sale = ShopFactory.Sale(sellItemBase64, amount),
                pricing = pricing,
                direction = direction,
            ),
        )
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
    private fun renderSign(shop: net.badgersmc.em.domain.shop.Shop): Boolean {
        val state = signLoc.block.state as? Sign ?: return false
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
        return state.update(true, false)
    }

    private fun publishShopCreated(ownerId: UUID) {
        try {
            Bukkit.getPluginManager().callEvent(ShopCreatedEvent(ownerId))
        } catch (_: Throwable) {
            // External listener failure must not roll back the create.
        }
    }
}
