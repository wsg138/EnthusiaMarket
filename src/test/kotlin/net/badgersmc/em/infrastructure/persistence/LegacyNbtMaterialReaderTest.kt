package net.badgersmc.em.infrastructure.persistence

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LegacyNbtMaterialReaderTest {
    @Test
    fun `reads a namespaced top-level material id`() {
        val payload = nbt {
            writeByte(10)
            writeUTF("")
            writeByte(8)
            writeUTF("id")
            writeUTF("minecraft:diamond")
            writeByte(0)
        }

        assertEquals("DIAMOND", LegacyNbtMaterialReader.extract(payload))
    }

    @Test
    fun `skips nested compound and list payloads before the material id`() {
        val payload = nbt {
            writeByte(10)
            writeUTF("Item")
            writeByte(10)
            writeUTF("tag")
            writeByte(1)
            writeUTF("Count")
            writeByte(1)
            writeByte(0)
            writeByte(9)
            writeUTF("values")
            writeByte(3)
            writeInt(2)
            writeInt(7)
            writeInt(8)
            writeByte(8)
            writeUTF("id")
            writeUTF("stone")
            writeByte(0)
        }

        assertEquals("STONE", LegacyNbtMaterialReader.extract(payload))
    }

    @Test
    fun `rejects truncated payloads`() {
        assertNull(LegacyNbtMaterialReader.extract(gzip(byteArrayOf(10, 0))))
    }

    @Test
    fun `rejects oversized decompressed payloads`() {
        assertNull(LegacyNbtMaterialReader.extract(gzip(ByteArray(1_048_577))))
    }

    private fun nbt(block: DataOutputStream.() -> Unit): ByteArray {
        val plain = ByteArrayOutputStream()
        DataOutputStream(plain).use { output -> output.block() }
        return gzip(plain.toByteArray())
    }

    private fun gzip(data: ByteArray): ByteArray {
        val compressed = ByteArrayOutputStream()
        GZIPOutputStream(compressed).use { it.write(data) }
        return compressed.toByteArray()
    }
}
