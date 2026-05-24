package net.badgersmc.em.application

import org.bukkit.inventory.ItemStack
import org.bukkit.util.io.BukkitObjectInputStream
import org.bukkit.util.io.BukkitObjectOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * Serializes and deserializes ItemStacks to/from base64 strings.
 * Used for persisting item data in the database.
 */
object ItemStackSerializer {
    fun serialize(item: ItemStack): String {
        val baos = ByteArrayOutputStream()
        BukkitObjectOutputStream(baos).use { it.writeObject(item) }
        return Base64.getEncoder().encodeToString(baos.toByteArray())
    }

    fun deserialize(base64: String): ItemStack? {
        return try {
            val bytes = Base64.getDecoder().decode(base64)
            val bais = ByteArrayInputStream(bytes)
            BukkitObjectInputStream(bais).use { it.readObject() as ItemStack }
        } catch (_: Exception) { null }
    }
}