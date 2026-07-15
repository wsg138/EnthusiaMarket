package net.badgersmc.em.interaction.bedrock

import net.badgersmc.em.domain.shop.Shop
import net.badgersmc.em.domain.shop.SignDirection
import net.badgersmc.nexus.i18n.LangService
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.geysermc.cumulus.form.SimpleForm
import org.geysermc.cumulus.response.SimpleFormResponse
import java.util.logging.Logger

/**
 * Bedrock Cumulus SimpleForm for shop purchases (REQ-013).
 * Shows the direction-appropriate action button: BUY for SELL shops,
 * SELL for BUY shops.
 */
class BedrockPurchaseForm(
    player: Player,
    private val shop: Shop,
    private val onBuy: () -> Unit,
    private val onSell: () -> Unit,
    logger: Logger,
    lang: LangService
) : BedrockMenuBase(player, logger, lang) {

    override fun buildForm(): SimpleForm {
        val ownerName = Bukkit.getOfflinePlayer(shop.owner).name
            ?: lang.legacy("common.unknown_player")
        val statusKey = if (shop.frozen) "bedrock.purchase.status_frozen" else "bedrock.purchase.status_active"
        val statusMm = lang.raw(statusKey)

        val builder = SimpleForm.builder()
            .title(lang.legacy("bedrock.purchase.title"))
            .content(lang.legacy(
                "bedrock.purchase.content",
                "amount" to shop.sellAmount,
                "price" to shop.costAmount,
                "owner" to ownerName,
                "status" to statusMm
            ))

        // Show the direction-appropriate action button
        builder.button(
            if (shop.direction == SignDirection.BUY)
                lang.legacy("bedrock.purchase.button_sell")
            else
                lang.legacy("bedrock.purchase.button_buy")
        )
        // Back button — always index 1
        builder.button(lang.legacy("bedrock.purchase.button_back"))

        return builder
            .validResultHandler { response: SimpleFormResponse ->
                when (response.clickedButtonId()) {
                    0 -> {
                        if (shop.direction == SignDirection.BUY) onSell() else onBuy()
                    }
                    1 -> { /* back */ }
                }
            }
            .build()
    }
}
