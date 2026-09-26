package net.badgersmc.em.infrastructure.lumaguilds

import net.badgersmc.nexus.paper.listeners.Listener
import kotlin.test.Test
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
    fun `only registration owner is auto-discovered by Nexus`() {
        assertNull(GuildDisbandedEventListener::class.java.getAnnotation(Listener::class.java))
        assertNull(GuildVisualChangeListener::class.java.getAnnotation(Listener::class.java))
        assertNotNull(LumaGuildsListenerRegistration::class.java.getAnnotation(Listener::class.java))
    }
}
