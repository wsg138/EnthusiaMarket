package net.badgersmc.em.infrastructure.lumaguilds

import io.mockk.every
import io.mockk.mockk
import net.lumalyte.lg.api.GuildLookup
import net.lumalyte.lg.api.GuildSummary
import net.lumalyte.lg.api.GuildBannerDesignSummary
import net.lumalyte.lg.api.GuildBannerPatternSummary
import net.lumalyte.lg.api.GuildVisualLookup
import net.lumalyte.lg.api.GuildVisualSummary
import org.bukkit.plugin.ServicePriority
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LumaGuildsGuildProviderTest {

    companion object {
        private const val INVALID_UUID = "not-a-uuid"
    }

    private val guildId = UUID.randomUUID()
    private val playerId = UUID.randomUUID()
    private val lookup = mockk<GuildLookup>(relaxed = true)
    private val visualLookup = mockk<GuildVisualLookup>(relaxed = true)
    private val provider = LumaGuildsGuildProvider()
    private lateinit var server: ServerMock

    @BeforeEach
    fun setUp() {
        server = MockBukkit.mock()
        val plugin = MockBukkit.createMockPlugin("LumaGuilds")
        server.servicesManager.register(GuildLookup::class.java, lookup, plugin, ServicePriority.Normal)
        server.servicesManager.register(GuildVisualLookup::class.java, visualLookup, plugin, ServicePriority.Normal)
    }

    @AfterEach
    fun tearDown() {
        MockBukkit.unmock()
    }

    // --- guildOf ---

    @Test
    fun `guildOf returns GuildRef for player's first guild`() {
        every { lookup.getPlayerGuildIds(playerId) } returns setOf(guildId)
        every { lookup.getGuild(guildId) } returns GuildSummary(guildId, "TestGuild", null, null)

        val result = provider.guildOf(playerId)

        assertNotNull(result)
        assertEquals(guildId.toString(), result.id)
        assertEquals("TestGuild", result.name)
    }

    @Test
    fun `guildOf returns null when player has no guilds`() {
        every { lookup.getPlayerGuildIds(playerId) } returns emptySet()
        assertNull(provider.guildOf(playerId))
    }

    @Test
    fun `guildOf returns null when guild lookup fails`() {
        every { lookup.getPlayerGuildIds(playerId) } returns setOf(guildId)
        every { lookup.getGuild(guildId) } returns null
        assertNull(provider.guildOf(playerId))
    }

    // --- guildById ---

    @Test
    fun `guildById returns GuildRef for valid UUID string`() {
        every { lookup.getGuild(guildId) } returns GuildSummary(guildId, "MyGuild", null, null)

        val result = provider.guildById(guildId.toString())

        assertNotNull(result)
        assertEquals(guildId.toString(), result.id)
        assertEquals("MyGuild", result.name)
    }

    @Test
    fun `guildById returns null for invalid UUID string`() {
        assertNull(provider.guildById(INVALID_UUID))
    }

    @Test
    fun `guildById returns null when guild not found`() {
        every { lookup.getGuild(guildId) } returns null
        assertNull(provider.guildById(guildId.toString()))
    }

    @Test
    fun `visualById maps leader and ordered public banner`() {
        val leaderId = UUID.randomUUID()
        every { visualLookup.getGuildVisual(guildId) } returns GuildVisualSummary(
            leaderId,
            GuildBannerDesignSummary(
                "BLUE",
                listOf(
                    GuildBannerPatternSummary("STRIPE_TOP", "WHITE"),
                    GuildBannerPatternSummary("CROSS", "RED"),
                ),
            ),
        )

        val result = provider.visualById(guildId.toString())

        assertEquals(leaderId, result?.leaderId)
        assertEquals("BLUE", result?.banner?.baseColor)
        assertEquals(listOf("STRIPE_TOP", "CROSS"), result?.banner?.patterns?.map { it.type })
    }

    // --- tag/emoji normalisation flows through GuildSummary ---

    @Test
    fun `guildById normalises legacy tag to MiniMessage`() {
        every { lookup.getGuild(guildId) } returns GuildSummary(guildId, "G", "&aHi", null)
        val result = provider.guildById(guildId.toString())
        assertNotNull(result)
        assertTrue(result.tag.contains("<green>") || result.tag.contains("green"), "legacy &a should become MiniMessage: ${result.tag}")
    }

    // --- isMember ---

    @Test
    fun `isMember returns true when member exists`() {
        every { lookup.isMember(playerId, guildId) } returns true
        assertTrue(provider.isMember(playerId, guildId.toString()))
    }

    @Test
    fun `isMember returns false when member does not exist`() {
        every { lookup.isMember(playerId, guildId) } returns false
        assertFalse(provider.isMember(playerId, guildId.toString()))
    }

    @Test
    fun `isMember returns false for invalid guild ID`() {
        assertFalse(provider.isMember(playerId, INVALID_UUID))
    }

    // --- hasShopPermission ---

    @Test
    fun `hasShopPermission maps GuildPermission to RankPermission name`() {
        every { lookup.hasShopPermission(playerId, guildId, "EDIT_SHOP_STOCK") } returns true
        assertTrue(
            provider.hasShopPermission(
                playerId, guildId.toString(), net.badgersmc.em.domain.ports.GuildProvider.GuildPermission.MANAGE_SHOPS,
            ),
        )
    }

    @Test
    fun `hasShopPermission returns false for invalid guild ID`() {
        assertFalse(
            provider.hasShopPermission(
                playerId, INVALID_UUID, net.badgersmc.em.domain.ports.GuildProvider.GuildPermission.MANAGE_SHOPS,
            ),
        )
    }

    // --- deprecated rank-based hasPermission delegates to hasRankAtLeast ---

    @Test
    @Suppress("DEPRECATION")
    fun `hasPermission delegates to hasRankAtLeast`() {
        every { lookup.hasRankAtLeast(playerId, guildId, "Officer") } returns true
        assertTrue(provider.hasPermission(playerId, guildId.toString(), "Officer"))
    }

    @Test
    @Suppress("DEPRECATION")
    fun `hasPermission returns false for invalid guild ID`() {
        assertFalse(provider.hasPermission(playerId, INVALID_UUID, "Officer"))
    }

    // --- bank ---

    @Test
    fun `bankBalance delegates to lookup`() {
        every { lookup.getBankBalance(guildId) } returns 5000L
        assertEquals(5000L, provider.bankBalance(guildId.toString()))
    }

    @Test
    fun `bankBalance returns zero for invalid guild ID`() {
        assertEquals(0L, provider.bankBalance(INVALID_UUID))
    }

    @Test
    fun `bankWithdraw returns true on success`() {
        every { lookup.bankWithdraw(guildId, any(), 1000, any()) } returns true
        assertTrue(provider.bankWithdraw(guildId.toString(), 1000L))
    }

    @Test
    fun `bankWithdraw returns false on failure`() {
        every { lookup.bankWithdraw(guildId, any(), 1000, any()) } returns false
        assertFalse(provider.bankWithdraw(guildId.toString(), 1000L))
    }

    @Test
    fun `bankWithdraw returns false for invalid guild ID`() {
        assertFalse(provider.bankWithdraw(INVALID_UUID, 1000L))
    }

    @Test
    fun `bankDeposit returns true on success`() {
        every { lookup.bankDeposit(guildId, any(), 500, any()) } returns true
        assertTrue(provider.bankDeposit(guildId.toString(), 500L))
    }

    @Test
    fun `bankDeposit returns false on failure`() {
        every { lookup.bankDeposit(guildId, any(), 500, any()) } returns false
        assertFalse(provider.bankDeposit(guildId.toString(), 500L))
    }

    @Test
    fun `bankDeposit returns false for invalid guild ID`() {
        assertFalse(provider.bankDeposit(INVALID_UUID, 500L))
    }

    // --- onDissolved / handleDisbanded ---

    @Test
    fun `onDissolved registers callback called by handleDisbanded`() {
        var captured: String? = null
        provider.onDissolved { captured = it }
        provider.handleDisbanded(guildId.toString())
        assertEquals(guildId.toString(), captured)
    }

    @Test
    fun `multiple dissolve handlers all receive notification`() {
        val results = mutableListOf<String>()
        provider.onDissolved { results.add("a:$it") }
        provider.onDissolved { results.add("b:$it") }
        provider.handleDisbanded(guildId.toString())
        assertEquals(listOf("a:${guildId}", "b:${guildId}"), results)
    }
}
