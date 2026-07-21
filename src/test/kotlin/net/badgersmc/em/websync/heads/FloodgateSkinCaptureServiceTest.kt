package net.badgersmc.em.websync.heads

import net.badgersmc.em.websync.DeliveryOutcome
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class FloodgateSkinCaptureServiceTest {
    @TempDir lateinit var directory: File

    @Test
    fun `returns false when render work cannot be queued`() {
        val store = BedrockHeadStore(
            directory,
            config = { null },
            uploader = { _, _, _, _ -> DeliveryOutcome.Retry() },
            published = {},
        )
        val capture = FloodgateSkinCaptureService(store)
        capture.close()

        assertFalse(capture.captureProfileTexture(UUID.randomUUID(), "https://textures.minecraft.net/texture/${"a".repeat(64)}"))
        assertEquals(1, capture.status().dropped)
        assertEquals(0, capture.status().accepted)
        store.close()
    }
}
