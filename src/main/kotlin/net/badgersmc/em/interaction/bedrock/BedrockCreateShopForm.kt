package net.badgersmc.em.interaction.bedrock

import net.badgersmc.em.domain.shop.Shop
import net.badgersmc.em.domain.shop.ShopRepository
import org.bukkit.Location
import org.bukkit.entity.Player
import org.geysermc.cumulus.form.CustomForm
import org.geysermc.cumulus.response.CustomFormResponse
import java.util.UUID
import java.util.logging.Logger

/**
 * Bedrock Cumulus CustomForm for creating a shop (REQ-012).
 * Player enters item name, price per item, and amount per trade via text inputs.
 * Saves via ShopRepository on submission.
 * Index 0 = label, index 1+ = inputs.
 */
class BedrockCreateShopForm(
    player: Player,
    private val stallOwner: UUID,
    private val stallId: String,
    private val signLoc: Location,
    private val containerLoc: Location,
    private val shopRepository: ShopRepository,
    logger: Logger
) : BedrockMenuBase(player, logger) {

    override fun buildForm(): CustomForm {
        return CustomForm.builder()
            .title("Create Shop")
            .label("Set your shop's item and price")
            .input("Item (material name)", "e.g. diamond", "diamond")
            .input("Price per item", "e.g. 10", "10")
            .input("Amount per trade", "e.g. 1", "1")
            .validResultHandler { response: CustomFormResponse ->
                val itemName = response.asInput(1) ?: ""
                val priceText = response.asInput(2) ?: ""
                val amountText = response.asInput(3) ?: "1"

                val price = priceText.toIntOrNull()
                val amount = amountText.toIntOrNull() ?: 1

                if (itemName.isBlank() || price == null || price <= 0) {
                    player.sendMessage("§cInvalid item or price")
                    return@validResultHandler
                }

                // Create the shop with placeholder serialization
                // In production, the item would be serialized from the player's inventory
                val shop = Shop(
                    stallId = stallId,
                    owner = stallOwner,
                    signWorld = signLoc.world?.name ?: "world",
                    signX = signLoc.blockX,
                    signY = signLoc.blockY,
                    signZ = signLoc.blockZ,
                    containerWorld = containerLoc.world?.name ?: "world",
                    containerX = containerLoc.blockX,
                    containerY = containerLoc.blockY,
                    containerZ = containerLoc.blockZ,
                    sellItem = itemName, // simplified — would be base64 ItemStack
                    sellAmount = amount,
                    costItem = price.toString(), // simplified
                    costAmount = price
                )
                shopRepository.upsert(shop)
                player.sendMessage("§aShop created!")
            }
            .build()
    }
}