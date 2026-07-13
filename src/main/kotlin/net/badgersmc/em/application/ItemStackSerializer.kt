package net.badgersmc.em.application

import org.bukkit.inventory.ItemStack
import org.bukkit.util.io.BukkitObjectInputStream
import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.logging.Logger

object ItemStackSerializer {
    private val log = Logger.getLogger(ItemStackSerializer::class.java.name)

    fun serialize(item: ItemStack): String =
        Base64.getEncoder().encodeToString(item.serializeAsBytes())

    fun deserialize(base64: String): ItemStack? {
        val bytes = try {
            Base64.getDecoder().decode(base64)
        } catch (e: IllegalArgumentException) {
            log.warning("ItemStack deserialization: bad base64: ${e.message}")
            return null
        }
        val item = runCatching { ItemStack.deserializeBytes(bytes) }.getOrNull()
            ?: runCatching {
                ByteArrayInputStream(bytes).let { bais ->
                    BukkitObjectInputStream(bais).use { it.readObject() as ItemStack }
                }
            }.getOrNull()
            ?: return null
        // Round-trip through serialize/deserialize to normalize data version.
        // Items stored before a Minecraft/Paper update carry an old data version
        // that won't match current player-inventory items via equals/hashCode.
        // No-op if the round-trip fails — use original.
        return runCatching { ItemStack.deserialize(item.serialize()) }.getOrDefault(item)
    }
}
