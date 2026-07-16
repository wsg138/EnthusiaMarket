package net.badgersmc.em.websync.heads

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.geysermc.geyser.api.event.EventBus
import org.geysermc.geyser.api.event.EventRegistrar
import org.geysermc.geyser.api.event.bedrock.SessionSkinApplyEvent
import org.geysermc.geyser.api.skin.Skin
import org.geysermc.geyser.api.skin.SkinData
import org.geysermc.geyser.api.skin.SkinGeometry
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

class GeyserSessionSkinListenerTest {
    @Test
    fun `linkage failure unregisters once and prevents later capture`() {
        val captures = AtomicInteger()
        val bus = mockk<EventBus<EventRegistrar>>(relaxed = true)
        val listener = GeyserSessionSkinListener(BedrockSkinCapture { _, _ -> captures.incrementAndGet() }, bus)
        val broken = mockk<SessionSkinApplyEvent>()
        every { broken.bedrock() } throws NoSuchMethodError()

        dispatch(listener, broken)
        dispatch(listener, compatibleEvent())
        listener.close()

        assertEquals(0, captures.get())
        verify(exactly = 1) { bus.unregisterAll(listener) }
    }

    @Test
    fun `ordinary exception leaves listener registered and compatible events capture`() {
        val captures = AtomicInteger()
        val bus = mockk<EventBus<EventRegistrar>>(relaxed = true)
        val listener = GeyserSessionSkinListener(BedrockSkinCapture { _, _ -> captures.incrementAndGet() }, bus)
        val malformed = mockk<SessionSkinApplyEvent>()
        every { malformed.bedrock() } throws IllegalArgumentException()

        dispatch(listener, malformed)
        dispatch(listener, compatibleEvent())
        assertEquals(1, captures.get())
        verify(exactly = 0) { bus.unregisterAll(listener) }
        listener.close()
        verify(exactly = 1) { bus.unregisterAll(listener) }
    }

    private fun dispatch(listener: GeyserSessionSkinListener, event: SessionSkinApplyEvent) {
        listener.javaClass.getDeclaredMethod("onSkin", SessionSkinApplyEvent::class.java).apply { isAccessible = true }
            .invoke(listener, event)
    }

    private fun compatibleEvent(): SessionSkinApplyEvent {
        val event = mockk<SessionSkinApplyEvent>()
        val skin = Skin("", byteArrayOf(1))
        val geometry = SkinGeometry(SkinGeometry.WIDE.geometryName(), "")
        every { event.bedrock() } returns true
        every { event.skinData() } returns SkinData(skin, null, geometry)
        every { event.uuid() } returns UUID.randomUUID()
        return event
    }
}
