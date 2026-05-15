package net.badgersmc.em.infrastructure.vault

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.milkbowl.vault.economy.Economy
import net.milkbowl.vault.economy.EconomyResponse
import org.bukkit.OfflinePlayer
import org.bukkit.Server
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VaultEconomyProviderTest {
    private val server: Server = mockk(relaxed = true)
    private val economy: Economy = mockk()
    private val offline: OfflinePlayer = mockk(relaxed = true)
    private val uuid = UUID.randomUUID()

    private fun provider(): VaultEconomyProvider {
        every { server.getOfflinePlayer(uuid) } returns offline
        return VaultEconomyProvider(server, economy)
    }

    @Test fun `balance reads from vault as truncated long`() {
        every { economy.getBalance(offline) } returns 1234.99
        assertEquals(1234L, provider().balance(uuid))
    }

    @Test fun `withdraw returns true when vault reports success`() {
        every { economy.withdrawPlayer(offline, 50.0) } returns
            EconomyResponse(50.0, 0.0, EconomyResponse.ResponseType.SUCCESS, "")
        assertTrue(provider().withdraw(uuid, 50L))
        verify { economy.withdrawPlayer(offline, 50.0) }
    }

    @Test fun `withdraw returns false on vault failure`() {
        every { economy.withdrawPlayer(offline, 50.0) } returns
            EconomyResponse(0.0, 0.0, EconomyResponse.ResponseType.FAILURE, "insufficient")
        assertFalse(provider().withdraw(uuid, 50L))
    }

    @Test fun `deposit returns true on success`() {
        every { economy.depositPlayer(offline, 75.0) } returns
            EconomyResponse(75.0, 75.0, EconomyResponse.ResponseType.SUCCESS, "")
        assertTrue(provider().deposit(uuid, 75L))
    }
}
