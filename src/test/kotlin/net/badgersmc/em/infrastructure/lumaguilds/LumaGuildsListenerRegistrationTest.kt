package net.badgersmc.em.infrastructure.lumaguilds

import net.badgersmc.nexus.paper.listeners.Listener
import java.util.UUID
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
    fun `enabled configuration requires the plugin`() {
        assertTrue(LumaGuildsListenerRegistration.shouldRegister(configured = true, available = true))
        assertFalse(LumaGuildsListenerRegistration.shouldRegister(configured = true, available = false))
    }

    @Test
    fun `event discovery supports current and legacy LumaGuilds packages`() {
        assertEquals(
            listOf(
                "net.lumalyte.lg.api.events.GuildDisbandedEvent",
                "net.lumalyte.lg.domain.events.GuildDisbandedEvent",
            ),
            LumaGuildsEventAccess.candidates("GuildDisbandedEvent"),
        )
    }

    @Test
    fun `current LumaGuilds event API is discovered`() {
        val event = LumaGuildsEventAccess.resolve(
            LumaGuildsListenerRegistrationTest::class.java.classLoader,
            "GuildDisbandedEvent",
        )
        assertNotNull(event)
        assertEquals("net.lumalyte.lg.api.events.GuildDisbandedEvent", event.name)
    }

    @Test
    fun `guild id extraction supports direct and nested event shapes`() {
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

    private data class FakeVisualEvent(val guildId: UUID)
    private data class FakeGuild(val id: UUID)
    private data class FakeDisbandedEvent(val guild: FakeGuild)
}
