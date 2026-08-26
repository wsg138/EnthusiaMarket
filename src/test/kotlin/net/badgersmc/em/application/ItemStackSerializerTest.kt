package net.badgersmc.em.application

import org.bukkit.Material
import org.bukkit.enchantments.Enchantment
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.Damageable
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.mockbukkit.mockbukkit.MockBukkit
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
            val baos = java.io.ByteArrayOutputStream()
            org.bukkit.util.io.BukkitObjectOutputStream(baos).use { it.writeObject(item) }
            Base64.getEncoder().encodeToString(baos.toByteArray())
        }
        assertEquals(item, ItemStackSerializer.deserialize(legacy))
    }

    @Test fun `deserialize returns null on garbage`() {
        assertEquals(null, ItemStackSerializer.deserialize("not-base64-or-item"))
    }

    // --- REQ-300: data component preservation ---

    @Test fun `serialize-deserialize preserves display name`() {
        val item = ItemStack(Material.DIAMOND_SWORD)
        val meta = item.itemMeta
        meta.setDisplayName("\u00a76Legendary Blade")
        item.itemMeta = meta

        val restored = ItemStackSerializer.deserialize(ItemStackSerializer.serialize(item))
        assertNotNull(restored)
        assertEquals("\u00a76Legendary Blade", restored!!.itemMeta.displayName)
        assertEquals(Material.DIAMOND_SWORD, restored.type)
    }

    @Test fun `serialize-deserialize preserves enchantments`() {
        val item = ItemStack(Material.DIAMOND_PICKAXE)
        val meta = item.itemMeta
        meta.addEnchant(Enchantment.EFFICIENCY, 5, true)
        meta.addEnchant(Enchantment.UNBREAKING, 3, true)
        item.itemMeta = meta

        val restored = ItemStackSerializer.deserialize(ItemStackSerializer.serialize(item))
        assertNotNull(restored)
        assertEquals(2, restored!!.itemMeta.enchants.size)
        assertEquals(5, restored.itemMeta.getEnchantLevel(Enchantment.EFFICIENCY))
        assertEquals(3, restored.itemMeta.getEnchantLevel(Enchantment.UNBREAKING))
    }

    @Test fun `serialize-deserialize preserves lore`() {
        val item = ItemStack(Material.NETHERITE_INGOT)
        val meta = item.itemMeta
        meta.lore = listOf("\u00a77A rare metal", "\u00a78Forged in fire")
        item.itemMeta = meta

        val restored = ItemStackSerializer.deserialize(ItemStackSerializer.serialize(item))
        assertNotNull(restored)
        assertEquals(2, restored!!.itemMeta.lore!!.size)
        assertEquals("\u00a77A rare metal", restored.itemMeta.lore!![0])
        assertEquals("\u00a78Forged in fire", restored.itemMeta.lore!![1])
    }

    @Test fun `serialize-deserialize preserves unbreakable and custom model data`() {
        val item = ItemStack(Material.ELYTRA)
        val meta = item.itemMeta
        meta.isUnbreakable = true
        meta.setCustomModelData(42)
        item.itemMeta = meta

        val restored = ItemStackSerializer.deserialize(ItemStackSerializer.serialize(item))
        assertNotNull(restored)
        assertEquals(true, restored!!.itemMeta.isUnbreakable)
        assertEquals(42, restored.itemMeta.customModelData)
    }

    @Test fun `deserialize returns same result as deserializeBytes alone`() {
        // REQ-300: deserialize() must not alter items beyond what deserializeBytes()
        // already does. The legacy ItemStack.deserialize(item.serialize()) Map round-trip
        // was added for data-version normalization but strips modern Paper 1.21+ data
        // components on the real server (e.g. minecraft:ominous on trial keys).
        // This test proves deserializeBytes alone is sufficient and the round-trip
        // is unnecessary — any deviation is a bug.
        val original = ItemStack(Material.DIAMOND_SWORD)
        val meta = original.itemMeta
        meta.setDisplayName("Test Blade")
        meta.isUnbreakable = true
        original.itemMeta = meta

        val serialized = ItemStackSerializer.serialize(original)
        val bytes = Base64.getDecoder().decode(serialized)
        val fromBytesOnly = ItemStack.deserializeBytes(bytes)
        val deserialized = ItemStackSerializer.deserialize(serialized)

        assertNotNull(fromBytesOnly)
        assertNotNull(deserialized)
        assertEquals(fromBytesOnly, deserialized,
            "deserialize() must return the same ItemStack as deserializeBytes() alone. " +
            "The Map round-trip (ItemStack.deserialize(item.serialize())) in the current " +
            "implementation strips modern data components on Paper 1.21+ servers.")
    }

    @Test fun `normalizeDamage makes absent-damage and zero-damage items serialize identically`() {
        // Pitfall #22b/#22d: absent Damage NBT tag vs Damage:0 produce different bytes.
        // normalizeDamage() force-sets damage=0 on undamaged items before serialization.
        val noDamage = ItemStack(Material.DIAMOND_SWORD)
        val zeroDamage = ItemStack(Material.DIAMOND_SWORD)
        val zdm = zeroDamage.itemMeta as Damageable
        zdm.damage = 0
        zeroDamage.itemMeta = zdm

        val noDamageSerialized = ItemStackSerializer.serialize(noDamage)
        val zeroDamageSerialized = ItemStackSerializer.serialize(zeroDamage)

        assertEquals(noDamageSerialized, zeroDamageSerialized,
            "Items with absent-damage and damage=0 must serialize to identical base64")
    }
}
