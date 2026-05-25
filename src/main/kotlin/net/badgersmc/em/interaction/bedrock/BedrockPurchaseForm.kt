package net.badgersmc.em.interaction.bedrock

import net.badgersmc.em.domain.shop.Shop
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
    logger: Logger
) : BedrockMenuBase(player, logger) {

    override fun buildForm(): SimpleForm {
        val ownerName = Bukkit.getOfflinePlayer(shop.owner).name ?: "Unknown"

        return SimpleForm.builder()
            .title("Shop")
            .content("Item: ${shop.sellAmount}x\nPrice: ${shop.costAmount} each\nOwner: $ownerName\nStatus: ${if (shop.frozen) "§cFrozen" else "§aActive"}")
            .button("§a§lBUY")
            .button("§e§lSELL")
            .button("§7Back")
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