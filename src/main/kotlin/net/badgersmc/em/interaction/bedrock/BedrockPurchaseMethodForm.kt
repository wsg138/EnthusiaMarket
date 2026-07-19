package net.badgersmc.em.interaction.bedrock

import net.badgersmc.em.application.StallBuyoutService
import net.badgersmc.em.domain.ports.GuildProvider
import net.badgersmc.em.domain.stall.StallId
import net.badgersmc.em.interaction.PurchaseFlow
import net.badgersmc.nexus.i18n.LangService
import org.bukkit.entity.Player
import org.geysermc.cumulus.form.SimpleForm
import java.util.logging.Logger

/** Native Bedrock method selection for an unowned stall. */
class BedrockPurchaseMethodForm(
    player: Player,
    private val stallId: StallId,
    private val price: Long,
    private val buyout: StallBuyoutService,
    guildProvider: GuildProvider?,
    logger: Logger,
    lang: LangService,
) : BedrockMenuBase(player, logger, lang) {
    private val guild = PurchaseFlow.eligibleGuild(player, guildProvider)

    override fun buildForm(): SimpleForm {
        val builder = SimpleForm.builder()
            .title(lang.legacy("purchase_sign.msg.method_title", "stall" to stallId.value, "price" to price))
            .content(lang.legacy("purchase_sign.msg.confirm_personal", "stall" to stallId.value, "price" to price))
            .button(lang.legacy("purchase_sign.msg.method_personal"))
        if (guild != null) builder.button(lang.legacy("purchase_sign.msg.method_guild") + " — ${guild.name}")
        builder.button(lang.legacy("purchase_sign.msg.confirm_no"))
        return builder.validResultHandler { handleButton(it.clickedButtonId()) }.build()
    }

    internal fun handleButton(button: Int) {
        when (button) {
            0 -> openConfirm(false)
            1 -> if (guild != null) openConfirm(true)
        }
    }

    private fun openConfirm(forGuild: Boolean) {
        BedrockPurchaseConfirmForm(
            player, stallId, price, forGuild, guild?.name, buyout, logger, lang,
        ).open(player)
    }
}
