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
    fun `disabled configuration never registers guild listeners`() {
        assertFalse(LumaGuildsListenerRegistration.shouldRegister(configured = false, available = true))
        assertFalse(LumaGuildsListenerRegistration.shouldRegister(configured = false, available = false))
    }

    @Test
    fun `enabled configuration requires LumaGuilds to be available`() {
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
    fun `guild id extraction supports direct and nested event shapes`() {
        val guildId = UUID.randomUUID()
        assertEquals(guildId, LumaGuildsEventAccess.guildId(FakeVisualEvent(guildId)))
        assertEquals(guildId, LumaGuildsEventAccess.guildId(FakeDisbandedEvent(FakeGuild(guildId))))
    }

    @Test
    fun `only registration owner is auto-discovered by Nexus`() {
        assertNull(GuildDisbandedEventListener::class.java.getAnnotation(Listener::class.java))
        assertNull(GuildVisualChangeListener::class.java.getAnnotation(Listener::class.java))
        assertNotNull(LumaGuildsListenerRegistration::class.java.getAnnotation(Listener::class.java))
    }

    private data class FakeVisualEvent(val guildId: UUID)
    private data class FakeGuild(val id: UUID)
    private data class FakeDisbandedEvent(val guild: FakeGuild)
}
