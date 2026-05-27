package net.badgersmc.em.interaction.bedrock

import net.badgersmc.em.domain.shop.Shop
import net.badgersmc.nexus.i18n.LangService
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.geysermc.cumulus.form.SimpleForm
import org.geysermc.cumulus.response.SimpleFormResponse
import java.util.logging.Logger

/**
 * Bedrock Cumulus SimpleForm for shop purchases (REQ-013).
 * Displays the shop's item, price, owner, and status, with BUY/SELL/Back buttons.
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
        // Status placeholder is a raw MiniMessage fragment so the outer template can deserialize it.
        val statusKey = if (shop.frozen) "bedrock.purchase.status_frozen" else "bedrock.purchase.status_active"
        val statusMm = lang.raw(statusKey)

        return SimpleForm.builder()
            .title(lang.legacy("bedrock.purchase.title"))
            .content(lang.legacy(
                "bedrock.purchase.content",
                "amount" to shop.sellAmount,
                "price" to shop.costAmount,
                "owner" to ownerName,
                "status" to statusMm
            ))
            .button(lang.legacy("bedrock.purchase.button_buy"))
            .button(lang.legacy("bedrock.purchase.button_sell"))
            .button(lang.legacy("bedrock.purchase.button_back"))
            .validResultHandler { response: SimpleFormResponse ->
                when (response.clickedButtonId()) {
                    0 -> onBuy()
                    1 -> onSell()
                    2 -> { /* back */ }
                }
            }
            .build()
    }
}