package net.badgersmc.em.infrastructure.bedrock

import io.mockk.every
import io.mockk.mockk
import org.bukkit.entity.Player
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * TDD-42: Failing test — UiDispatcher must exist and dispatch Bedrock
 * players to Cumulus forms instead of Bukkit GUI.
 *
 * Phase 1 (RED): All tests fail because UiDispatcher class doesn't exist.
 * Phase 2 (GREEN): After implementing UiDispatcher, all tests pass.
 */
class UiDispatcherTest {

    private val testUuid = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val javaUuid = UUID.fromString("00000000-0000-0000-0000-000000000002")

    // ========================
    // RED-phase: class must exist
    // ========================

    @Test
    fun `UiDispatcher class exists`() {
        // RED: throws ClassNotFoundException because UiDispatcher doesn't exist yet
        val cls = Class.forName("net.badgersmc.em.infrastructure.bedrock.UiDispatcher")
        assertNotNull(cls)
    }

    // ========================
    // These tests only pass after UiDispatcher is implemented (GREEN)
    // ========================

    @Test
    fun `UiDispatcher has isBedrockPlayer method`() {
        val cls = Class.forName("net.badgersmc.em.infrastructure.bedrock.UiDispatcher")
        val method = cls.getMethod("isBedrockPlayer", UUID::class.java)
        assertNotNull(method)
        assertTrue(method.returnType == Boolean::class.java)
    }

    @Test
    fun `UiDispatcher has dispatch method taking Player`() {
        val cls = Class.forName("net.badgersmc.em.infrastructure.bedrock.UiDispatcher")
        val method = cls.getMethod("dispatch", Player::class.java)
        assertNotNull(method)
    }

    @Test
    fun `isBedrockPlayer returns false when Floodgate is absent`() {
        // Graceful degradation: no FloodgateApi on classpath → false
        val dispatcher = UiDispatcher()
        assertFalse(dispatcher.isBedrockPlayer(testUuid))
    }

    @Test
    fun `dispatch does not throw for Java player`() {
        val dispatcher = UiDispatcher()
        val player = mockk<Player>(relaxed = true) {
            every { uniqueId } returns javaUuid
        }

        // Should complete without exception
        dispatcher.dispatch(player)
    }

    @Test
    fun `dispatch does not throw for any player type`() {
        val dispatcher = UiDispatcher()
        val player = mockk<Player>(relaxed = true) {
            every { uniqueId } returns testUuid
        }

        // Should not throw even though Floodgate is absent
        dispatcher.dispatch(player)
    }
}