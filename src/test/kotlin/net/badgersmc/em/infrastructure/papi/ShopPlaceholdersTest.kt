package net.badgersmc.em.infrastructure.papi

import io.mockk.every
import io.mockk.mockk
import net.badgersmc.em.domain.shop.ShopRepository
import net.badgersmc.em.domain.shop.ShopTransactionRepository
import org.bukkit.OfflinePlayer
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ShopPlaceholdersTest {

    private val uuid = UUID.randomUUID()
    private val player = mockk<OfflinePlayer> { every { uniqueId } returns uuid }

    private fun shops(repo: ShopRepository, tx: ShopTransactionRepository) = ShopPlaceholders(repo, tx)

    @Test fun `shops_owned counts the player's shops`() {
        val repo = mockk<ShopRepository> { every { countByOwner(uuid) } returns 2 }
        val tx = mockk<ShopTransactionRepository>(relaxed = true)
        assertEquals("2", shops(repo, tx).resolve(player, "shops_owned"))
    }

    @Test fun `shops_total counts all shops`() {
        val repo = mockk<ShopRepository> { every { countAll() } returns 5 }
        val tx = mockk<ShopTransactionRepository>(relaxed = true)
        assertEquals("5", shops(repo, tx).resolve(player, "shops_total"))
    }

    @Test fun `sales_unseen reads the tx repo`() {
        val repo = mockk<ShopRepository>(relaxed = true)
        val tx = mockk<ShopTransactionRepository> { every { countUnnotified(uuid) } returns 3 }
        assertEquals("3", shops(repo, tx).resolve(player, "sales_unseen"))
    }

    @Test fun `unknown key returns null`() {
        assertNull(shops(mockk(relaxed = true), mockk(relaxed = true)).resolve(player, "nope"))
    }

    @Test fun `player-scoped key with null player returns null`() {
        assertNull(shops(mockk(relaxed = true), mockk(relaxed = true)).resolve(null, "shops_owned"))
    }
}
