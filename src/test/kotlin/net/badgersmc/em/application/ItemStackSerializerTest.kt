package net.badgersmc.em.application

import org.bukkit.Material
import org.bukkit.inventory.ItemStack
import org.bukkit.util.io.BukkitObjectOutputStream
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockbukkit.mockbukkit.MockBukkit
import java.io.ByteArrayOutputStream
import java.util.Base64

class ItemStackSerializerTest {

    @BeforeEach fun setup() = MockBukkit.mock().let {}
    @AfterEach fun teardown() = MockBukkit.unmock()

    @Test fun `new-format round-trip preserves type and amount`() {
        val item = ItemStack(Material.DIAMOND_SWORD, 3)
        val restored = ItemStackSerializer.deserialize(ItemStackSerializer.serialize(item))
        assertEquals(item, restored)
    }

    @Test fun `deserialize reads a legacy BukkitObjectOutputStream blob`() {
        val item = ItemStack(Material.IRON_INGOT, 5)
        val legacy = run {
            val baos = ByteArrayOutputStream()
            BukkitObjectOutputStream(baos).use { it.writeObject(item) }
            Base64.getEncoder().encodeToString(baos.toByteArray())
        }
        assertEquals(item, ItemStackSerializer.deserialize(legacy))
    }

    @Test fun `deserialize returns null on garbage`() {
        assertNull(ItemStackSerializer.deserialize("not-base64-or-item"))
    }
}
