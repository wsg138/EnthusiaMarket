package net.badgersmc.em.interaction.bedrock

import net.badgersmc.em.domain.shop.Shop
import net.badgersmc.em.domain.shop.ShopRepository
import org.bukkit.entity.Player
import org.geysermc.cumulus.form.CustomForm
import org.geysermc.cumulus.response.CustomFormResponse
import java.util.logging.Logger

/**
 * Bedrock Cumulus CustomForm for editing a shop's settings (REQ-023).
 * Allows toggling freeze, hopper input, and hopper output via toggles.
 * Index 0 = label, index 1+ = toggles.
 */
class BedrockShopEditForm(
    player: Player,
    private val shop: Shop,
    private val shopRepository: ShopRepository,
    logger: Logger
) : BedrockMenuBase(player, logger) {

    override fun buildForm(): CustomForm {
        return CustomForm.builder()
            .title("Edit Shop")
            .label("§8Shop Settings")
            .toggle("Freeze shop (block trades)", shop.frozen)
            .toggle("Allow hopper input", shop.hopperAllowIn)
            .toggle("Allow hopper output", shop.hopperAllowOut)
            .validResultHandler { response: CustomFormResponse ->
                val frozen = response.asToggle(1) // index 1 = first toggle
                val hopperIn = response.asToggle(2)
                val hopperOut = response.asToggle(3)

                val updated = shop.copy(
                    frozen = frozen,
                    hopperAllowIn = hopperIn,
                    hopperAllowOut = hopperOut
                )
                shopRepository.upsert(updated)
                player.sendMessage("§aShop updated")
            }
            .build()
    }
}