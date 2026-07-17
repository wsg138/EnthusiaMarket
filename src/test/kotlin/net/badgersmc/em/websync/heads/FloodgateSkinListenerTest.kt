package net.badgersmc.em.websync.heads

import kotlin.test.Test
import kotlin.test.assertNotNull

class FloodgateSkinListenerTest {
    @Test
    fun `reflection integration can construct the one argument listener`() {
        assertNotNull(FloodgateSkinListener::class.java.getConstructor(FloodgateTextureCapture::class.java))
    }
}
