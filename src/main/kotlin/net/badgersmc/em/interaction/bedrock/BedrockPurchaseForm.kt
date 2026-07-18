package net.badgersmc.em.interaction.bedrock

import net.badgersmc.em.domain.shop.Shop
import net.badgersmc.em.domain.shop.SignDirection
import net.badgersmc.em.application.ContainerTradeResult
import net.badgersmc.nexus.i18n.LangService
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.geysermc.cumulus.form.SimpleForm
import org.geysermc.cumulus.response.SimpleFormResponse
import java.util.logging.Logger

/**
 * Bedrock Cumulus SimpleForm for shop purchases (REQ-013).
 * Shows the direction-appropriate action button: BUY for SELL shops,
 * SELL for BUY shops, and TRADE for barter shops.
 */
class BedrockPurchaseForm(
    player: Player,
    private val shop: Shop,
    private val onConfirm: () -> ContainerTradeResult,
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
        builder.button(lang.legacy(actionButtonKey(shop.direction)))
        // Back button — always index 1
        builder.button(lang.legacy("bedrock.purchase.button_back"))

        return builder
            .validResultHandler { response: SimpleFormResponse ->
                handleButton(response.clickedButtonId())
            }
            .build()
    }

    internal fun handleButton(buttonId: Int) {
        if (buttonId != 0) return
        report(onConfirm())
    }

    private fun report(result: ContainerTradeResult) {
        when (result) {
            is ContainerTradeResult.Success ->
                player.sendMessage(lang.msg("shop.trade.success", "message" to result.message))
            is ContainerTradeResult.Failure ->
                player.sendMessage(lang.msg("shop.trade.failure", "reason" to result.reason))
            is ContainerTradeResult.CompensationFailed -> {
                player.sendMessage(lang.msg("shop.trade.compensation_failed", "error" to result.error))
                player.sendMessage(lang.msg("shop.trade.compensation_note", "compensation" to result.compensation))
            }
        }
    }

    companion object {
        internal fun actionButtonKey(direction: SignDirection): String = when (direction) {
            SignDirection.SELL -> "bedrock.purchase.button_buy"
            SignDirection.BUY -> "bedrock.purchase.button_sell"
            SignDirection.TRADE -> "bedrock.purchase.button_trade"
        }
    }
}
