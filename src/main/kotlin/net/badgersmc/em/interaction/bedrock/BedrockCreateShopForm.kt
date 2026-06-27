package net.badgersmc.em.interaction.bedrock

import net.badgersmc.em.application.ShopFactory
import net.badgersmc.em.domain.shop.ShopRepository
import net.badgersmc.em.domain.shop.SignDirection
import net.badgersmc.nexus.i18n.LangService
import org.bukkit.Location
import org.bukkit.entity.Player
import org.geysermc.cumulus.form.CustomForm
import org.geysermc.cumulus.response.CustomFormResponse
import java.util.UUID
import java.util.logging.Logger

/**
 * Bedrock Cumulus CustomForm for creating a shop (REQ-012, TDD-60).
 *
 * The sell item is the player's main-hand item, captured by the listener and
 * passed in as a base64-serialised ItemStack ([sellItemBase64]) — NOT a raw
 * material name (the previous implementation stored "diamond" which broke
 * deserialization). The form collects only price + per-trade amount.
 */
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
) : BedrockMenuBase(player, logger, lang) {

    override fun buildForm(): CustomForm {
        return CustomForm.builder()
            .title("Create Shop")
            .label("Set your shop's price and amount. Sell item = the item in your hand.")
            .input("Price per trade", "e.g. 100", "100")
            .input("Amount per trade", "e.g. 1", "1")
            .validResultHandler { response: CustomFormResponse ->
                val priceText = response.asInput(1) ?: ""
                val amountText = response.asInput(2) ?: "1"
                val price = priceText.toLongOrNull()
                val amount = amountText.toIntOrNull() ?: 1
                if (price == null || price <= 0 || amount <= 0) {
                    player.sendMessage(lang.legacy("shop.create.invalid_input"))
                    return@validResultHandler
                }
                val shop = ShopFactory.build(
                    stallId = stallId, owner = stallOwner,
                    signWorld = signLoc.world?.name ?: "world",
                    signX = signLoc.blockX, signY = signLoc.blockY, signZ = signLoc.blockZ,
                    containerWorld = containerLoc.world?.name ?: "world",
                    containerX = containerLoc.blockX, containerY = containerLoc.blockY, containerZ = containerLoc.blockZ,
                    sellItemBase64 = sellItemBase64, sellAmount = amount, price = price,
                    direction = SignDirection.SELL,
                    searchEnabled = true,
                )
                shopRepository.upsert(shop)
                player.sendMessage(lang.legacy("shop.create.success"))
            }
            .build()
    }
}