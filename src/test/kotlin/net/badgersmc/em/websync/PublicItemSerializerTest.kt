package net.badgersmc.em.websync

import org.mockbukkit.mockbukkit.MockBukkit
import org.bukkit.Material
import org.bukkit.block.ShulkerBox
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.BlockStateMeta
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PublicItemSerializerTest {
    @BeforeEach
    fun setUp() { MockBukkit.mock() }

    @AfterEach
    fun tearDown() { MockBukkit.unmock() }

    @Test
    fun `potion ticks convert deterministically by flooring whole seconds`() {
        assertEquals(0, PublicItemSerializer.ticksToSeconds(19))
        assertEquals(1, PublicItemSerializer.ticksToSeconds(20))
        assertEquals(1, PublicItemSerializer.ticksToSeconds(39))
        assertEquals(2, PublicItemSerializer.ticksToSeconds(40))
        assertEquals(0, PublicItemSerializer.ticksToSeconds(-1))
    }

    @Test
    fun `shulker capacity uses contained item units for empty partial and full inventories`() {
        val empty = shulker(emptyList())
        assertEquals(0, empty.capacityUsed)
        assertEquals(1_728, empty.capacityMax)

        val partial = shulker(listOf(ItemStack(Material.STONE, 32), ItemStack(Material.ENDER_PEARL, 16)))
        assertEquals(48, partial.capacityUsed)
        assertEquals(1_728, partial.capacityMax)
        assertTrue(partial.capacityUsed!! <= partial.capacityMax!!)

        val full = shulker(List(27) { ItemStack(Material.STONE, 64) })
        assertEquals(1_728, full.capacityUsed)
        assertEquals(1_728, full.capacityMax)
    }

    private fun shulker(contents: List<ItemStack>): PublicContainer {
        val item = ItemStack(Material.SHULKER_BOX)
        val meta = item.itemMeta as BlockStateMeta
        val state = meta.blockState as ShulkerBox
        contents.forEachIndexed { slot, stack -> state.inventory.setItem(slot, stack) }
        meta.blockState = state
        item.itemMeta = meta
        return requireNotNull(PublicItemSerializer().serialize(item).metadata.container)
    }
}
