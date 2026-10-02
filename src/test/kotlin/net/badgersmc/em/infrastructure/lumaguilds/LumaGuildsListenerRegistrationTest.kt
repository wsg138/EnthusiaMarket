package net.badgersmc.em.infrastructure.lumaguilds

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.badgersmc.em.config.EnthusiaMarketConfig
import net.badgersmc.nexus.paper.listeners.Listener
import org.bukkit.Server
import org.bukkit.event.EventPriority
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.PluginManager
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LumaGuildsListenerRegistrationTest {

    @Test
    fun `disabled configuration never enables guild event registration`() {
        assertFalse(LumaGuildsListenerRegistration.shouldRegister(configured = false, available = true))
        assertFalse(LumaGuildsListenerRegistration.shouldRegister(configured = false, available = false))
    }

    @Test
    fun `disabled configuration returns before plugin lookup`() {
        val config = EnthusiaMarketConfig().also { it.lumaguilds.enabled = false }
        val plugin = mockk<JavaPlugin>()
        every { plugin.logger } returns mockk<Logger>(relaxed = true)

        registration(plugin, config).registerConfiguredListeners()

        verify(exactly = 0) { plugin.server }
    }

    @Test
    fun `enabled configuration requires the plugin`() {
        assertTrue(LumaGuildsListenerRegistration.shouldRegister(configured = true, available = true))
        assertFalse(LumaGuildsListenerRegistration.shouldRegister(configured = true, available = false))
    }

    @Test
    fun `plugin absent performs no event registration`() {
        val config = EnthusiaMarketConfig().also { it.lumaguilds.enabled = true }
        val manager = mockk<PluginManager>(relaxed = true)
        val plugin = pluginWith(manager)
        every { manager.getPlugin("LumaGuilds") } returns null

        registration(plugin, config).registerConfiguredListeners()

        verify(exactly = 1) { manager.getPlugin("LumaGuilds") }
        verify(exactly = 0) {
            manager.registerEvent(any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `enabled available plugin registers supported guild events`() {
        val config = EnthusiaMarketConfig().also { it.lumaguilds.enabled = true }
        val manager = mockk<PluginManager>(relaxed = true)
        val plugin = pluginWith(manager)
        val lumaGuilds = mockk<Plugin>()
        every { lumaGuilds.isEnabled } returns true
        every { manager.getPlugin("LumaGuilds") } returns lumaGuilds

        registration(plugin, config).registerConfiguredListeners()

        verify(exactly = 3) {
            manager.registerEvent(any(), any(), EventPriority.MONITOR, any(), plugin, true)
        }
    }

    @Test
    fun `current event package is preferred before the legacy package`() {
        assertEquals(
            listOf(
                "net.lumalyte.lg.api.events.GuildDisbandedEvent",
                "net.lumalyte.lg.domain.events.GuildDisbandedEvent",
            ),
            LumaGuildsEventAccess.candidates("GuildDisbandedEvent"),
        )
    }

    @Test
    fun `pinned supported LumaGuilds event API is discoverable`() {
        val event = LumaGuildsEventAccess.resolve(
            LumaGuildsListenerRegistrationTest::class.java.classLoader,
            "GuildDisbandedEvent",
        )
        assertNotNull(event)
        assertTrue(event.name in LumaGuildsEventAccess.candidates("GuildDisbandedEvent"))
    }

    @Test
    fun `guild id extraction supports current and legacy event shapes`() {
        val guildId = UUID.randomUUID()
        assertEquals(guildId, LumaGuildsEventAccess.guildId(FakeVisualEvent(guildId)))
        assertEquals(guildId, LumaGuildsEventAccess.guildId(FakeDisbandedEvent(FakeGuild(guildId))))
    }

    @Test
    fun `only version safe listeners are auto discovered by Nexus`() {
        assertNull(GuildDisbandedEventListener::class.java.getAnnotation(Listener::class.java))
        assertNotNull(GuildVisualChangeListener::class.java.getAnnotation(Listener::class.java))
        assertNotNull(LumaGuildsListenerRegistration::class.java.getAnnotation(Listener::class.java))
    }

    private fun registration(
        plugin: JavaPlugin,
        config: EnthusiaMarketConfig,
    ): LumaGuildsListenerRegistration = LumaGuildsListenerRegistration(
        plugin,
        config,
        mockk(relaxed = true),
        mockk(relaxed = true),
    )

    private fun pluginWith(manager: PluginManager): JavaPlugin {
        val server = mockk<Server>()
        val plugin = mockk<JavaPlugin>()
        every { server.pluginManager } returns manager
        every { plugin.server } returns server
        every { plugin.logger } returns mockk<Logger>(relaxed = true)
        return plugin
    }

    private data class FakeVisualEvent(val guildId: UUID)
    private data class FakeGuild(val id: UUID)
    private data class FakeDisbandedEvent(val guild: FakeGuild)
}
