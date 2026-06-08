package net.badgersmc.em.application

import org.bukkit.Material
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShopSearchServiceTest {

    private val svc = ShopSearchService()

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
}
