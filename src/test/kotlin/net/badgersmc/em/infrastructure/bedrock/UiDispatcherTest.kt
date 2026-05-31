package net.badgersmc.em.infrastructure.bedrock

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import net.kyori.adventure.text.Component
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

    companion object {
        private const val UI_DISPATCHER_CLASS = "net.badgersmc.em.infrastructure.bedrock.UiDispatcher"
    }

    private val testUuid = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val javaUuid = UUID.fromString("00000000-0000-0000-0000-000000000002")

    // ========================
    // RED-phase: class must exist
    // ========================

    @Test
    fun `UiDispatcher class exists`() {
        // RED: throws ClassNotFoundException because UiDispatcher doesn't exist yet
        val cls = Class.forName(UI_DISPATCHER_CLASS)
        assertNotNull(cls)
    }

    // ========================
    // These tests only pass after UiDispatcher is implemented (GREEN)
    // ========================

    @Test
    fun `UiDispatcher has isBedrockPlayer method`() {
        val cls = Class.forName(UI_DISPATCHER_CLASS)
        val method = cls.getMethod("isBedrockPlayer", UUID::class.java)
        assertNotNull(method)
        assertTrue(method.returnType == Boolean::class.java)
    }

    @Test
    fun `UiDispatcher has dispatch method taking Player`() {
        val cls = Class.forName(UI_DISPATCHER_CLASS)
        val method = cls.getMethod("dispatch", Player::class.java)
        assertNotNull(method)
    }

    @Test
    fun `isBedrockPlayer returns false when Floodgate is absent`() {
        // Graceful degradation: no FloodgateApi on classpath → false
        val dispatcher = UiDispatcher(mockk(relaxed = true))
        assertFalse(dispatcher.isBedrockPlayer(testUuid))
    }

    @Test
    fun `dispatch does not throw for Java player`() {
        val dispatcher = UiDispatcher(mockk(relaxed = true))
        val player = mockk<Player>(relaxed = true) {
            every { uniqueId } returns javaUuid
        }

        // Should complete without exception
        dispatcher.dispatch(player)
    }

    @Test
    fun `dispatch does not throw for any player type`() {
        val dispatcher = UiDispatcher(mockk(relaxed = true))
        val player = mockk<Player>(relaxed = true) {
            every { uniqueId } returns testUuid
        }

        // Should not throw even though Floodgate is absent
        dispatcher.dispatch(player)
    }

    @Test
    fun `dispatch sends fallback message for Bedrock player when Cumulus absent`() {
        val dispatcher = object : UiDispatcher(mockk(relaxed = true)) {
            override fun isBedrockPlayer(uuid: UUID): Boolean = true
        }
        val player = mockk<Player>(relaxed = true) {
            every { uniqueId } returns testUuid
        }

        // Should send a fallback chat message instead of throwing
        dispatcher.dispatch(player)

        // Verify sendMessage was called with the fallback message
        verify { player.sendMessage(any<Component>()) }
    }

    @Test
    fun `dispatch does not send message for Java player`() {
        val dispatcher = UiDispatcher(mockk(relaxed = true))
        val player = mockk<Player>(relaxed = true) {
            every { uniqueId } returns javaUuid
        }

        // Java player path: no form, no message
        dispatcher.dispatch(player)

        // Verify sendMessage was never called with the fallback message
        verify(exactly = 0) { player.sendMessage(any<Component>()) }
    }
}