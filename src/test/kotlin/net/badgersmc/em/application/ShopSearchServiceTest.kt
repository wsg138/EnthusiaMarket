package net.badgersmc.em.application

import net.badgersmc.em.domain.shop.Shop
import net.badgersmc.em.domain.shop.SignDirection
import org.bukkit.Material
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShopSearchServiceTest {

    private val svc = ShopSearchService()

    private fun shop(id: Long, searchEnabled: Boolean = true) = Shop(
        id = id, stallId = "s", owner = UUID.randomUUID(),
        signWorld = "world", signX = 1, signY = 2, signZ = 3,
        containerWorld = "world", containerX = 1, containerY = 1, containerZ = 1,
        sellItem = "s", sellAmount = 1, costItem = "c", costAmount = 10,
        direction = SignDirection.SELL, searchEnabled = searchEnabled,
    )

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

    @Test fun `search filters by sell material and searchEnabled preserving order`() {
        val shops = listOf(shop(1), shop(2, searchEnabled = false), shop(3))
        val resolver: (Shop) -> Material? = { s -> if (s.id == 2L) Material.DIAMOND else if (s.id == 1L) Material.DIAMOND else Material.IRON_INGOT }
        val result = svc.search(Material.DIAMOND, ShopSearchService.SearchMode.SELL, shops, resolver)
        // id 1 = diamond + enabled → in; id 2 = diamond but disabled → out; id 3 = iron → out
        assertEquals(listOf(1L), result.map { it.id })
    }
}
