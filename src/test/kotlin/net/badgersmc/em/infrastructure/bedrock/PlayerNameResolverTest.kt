package net.badgersmc.em.infrastructure.bedrock

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlayerNameResolverTest {
    private val javaUuid = UUID.fromString("217606e9-7047-4e8d-883e-1723e7fc93fa")
    private val floodgateUuid = UUID.fromString("00000000-0000-0000-0009-9b6db6db98dc")
    private val resolver = PlayerNameResolver()

    @BeforeTest fun mockBukkit() {
        mockkStatic(Bukkit::class)
        every { Bukkit.getOfflinePlayer(any<String>()) } returns player(UUID.randomUUID(), false)
    }

    @AfterTest fun unmockBukkit() = unmockkStatic(Bukkit::class)

    private fun player(uuid: UUID, playedBefore: Boolean): OfflinePlayer = mockk {
        every { uniqueId } returns uuid
        every { hasPlayedBefore() } returns playedBefore
    }

    @Test fun `exact java name resolves when the player has played`() {
        every { Bukkit.getOfflinePlayer("H3DGE5") } returns player(javaUuid, true)
        assertEquals(javaUuid, resolver.resolve("H3DGE5", "*")?.uniqueId)
    }

    @Test fun `bare bedrock name falls back to floodgate prefix`() {
        every { Bukkit.getOfflinePlayer("okcosmo") } returns player(UUID.randomUUID(), false)
        every { Bukkit.getOfflinePlayer("*okcosmo") } returns player(floodgateUuid, true)
        assertEquals(floodgateUuid, resolver.resolve("okcosmo", "*")?.uniqueId)
    }

    @Test fun `prefixed bedrock name resolves directly`() {
        every { Bukkit.getOfflinePlayer("*okcosmo") } returns player(floodgateUuid, true)
        assertEquals(floodgateUuid, resolver.resolve("*okcosmo", "*")?.uniqueId)
    }

    @Test fun `prefixed bedrock name also matches when bare lookup fails`() {
        every { Bukkit.getOfflinePlayer("*okcosmo") } returns player(UUID.randomUUID(), false)
        every { Bukkit.getOfflinePlayer("okcosmo") } returns player(floodgateUuid, true)
        assertEquals(floodgateUuid, resolver.resolve("*okcosmo", "*")?.uniqueId)
    }

    @Test fun `unknown name returns null`() {
        every { Bukkit.getOfflinePlayer("ghost") } returns player(UUID.randomUUID(), false)
        assertNull(resolver.resolve("ghost", "*"))
    }

    @Test fun `no floodgate prefix skips prefix fallback`() {
        every { Bukkit.getOfflinePlayer("okcosmo") } returns player(UUID.randomUUID(), false)
        assertNull(resolver.resolve("okcosmo", null))
    }
}
