package net.badgersmc.em.application

import org.bukkit.Material
import org.bukkit.block.ShulkerBox
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.BlockStateMeta
import org.bukkit.inventory.meta.BundleMeta
import org.mockbukkit.mockbukkit.MockBukkit
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShopSearchServiceTest {

    private val svc = ShopSearchService()

    @BeforeEach fun setUp() { MockBukkit.mock() }

    @AfterEach fun tearDown() { MockBukkit.unmock() }

    @Test fun `matches sell material when searchEnabled`() {
        assertTrue(svc.matches(true, Material.DIAMOND, Material.DIAMOND, ShopSearchService.SearchMode.SELL))
        assertTrue(svc.matches(true, Material.DIAMOND, Material.DIAMOND, ShopSearchService.SearchMode.ANY))
    }

    @Test fun `does not match a different material`() {
        assertFalse(svc.matches(true, Material.IRON_INGOT, Material.DIAMOND, ShopSearchService.SearchMode.SELL))
    }

    @Test fun `does not match when search disabled`() {
        assertFalse(svc.matches(false, Material.DIAMOND, Material.DIAMOND, ShopSearchService.SearchMode.SELL))
    }

    @Test fun `BUY mode never matches under the money model`() {
        assertFalse(svc.matches(true, Material.DIAMOND, Material.DIAMOND, ShopSearchService.SearchMode.BUY))
    }

    @Test fun `null sell material does not match`() {
        assertFalse(svc.matches(true, null, Material.DIAMOND, ShopSearchService.SearchMode.SELL))
    }

    @Test fun `finds an item nested inside a shulker box`() {
        val shulker = ItemStack(Material.SHULKER_BOX)
        val meta = shulker.itemMeta as BlockStateMeta
        val state = meta.blockState as ShulkerBox
        state.inventory.setItem(0, ItemStack(Material.GUNPOWDER, 64))
        meta.blockState = state
        shulker.itemMeta = meta

        val match = svc.findMatch(true, shulker, "gunpowder")

        assertTrue(match?.nested == true)
        assertTrue(match?.material == Material.GUNPOWDER)
    }

    @Test fun `supports material prefixes in nested containers`() {
        val shulker = ItemStack(Material.SHULKER_BOX)
        val meta = shulker.itemMeta as BlockStateMeta
        val state = meta.blockState as ShulkerBox
        state.inventory.setItem(0, ItemStack(Material.GUNPOWDER))
        meta.blockState = state
        shulker.itemMeta = meta

        assertTrue(svc.findMatch(true, shulker, "gunp")?.nested == true)
    }

    @Test fun `finds an item nested inside a bundle`() {
        val bundle = ItemStack(Material.BUNDLE)
        val meta = bundle.itemMeta as BundleMeta
        meta.addItem(ItemStack(Material.DIAMOND))
        bundle.itemMeta = meta

        val match = svc.findMatch(true, bundle, "diamond")

        assertTrue(match?.nested == true)
        assertTrue(match?.material == Material.DIAMOND)
    }

    @Test fun `does not inspect containers when search is disabled`() {
        assertTrue(svc.findMatch(false, ItemStack(Material.GUNPOWDER), "gunpowder") == null)
    }
}
