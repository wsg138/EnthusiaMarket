package net.badgersmc.em.infrastructure.persistence

import java.io.ByteArrayInputStream
import java.util.Locale
import java.util.zip.GZIPInputStream

/** Reads the top-level item id from the gzip NBT format used by legacy shop rows. */
internal object LegacyNbtMaterialReader {
    private const val TAG_END = 0
    private const val TAG_BYTE = 1
    private const val TAG_SHORT = 2
    private const val TAG_INT = 3
    private const val TAG_LONG = 4
    private const val TAG_FLOAT = 5
    private const val TAG_DOUBLE = 6
    private const val TAG_BYTE_ARRAY = 7
    private const val TAG_STRING = 8
    private const val TAG_LIST = 9
    private const val TAG_COMPOUND = 10
    private const val TAG_INT_ARRAY = 11
    private const val TAG_LONG_ARRAY = 12

    private const val MAX_UNCOMPRESSED_BYTES = 1_048_576
    private const val MAX_COLLECTION_ENTRIES = 65_536
    private const val MAX_DEPTH = 64

    fun extract(raw: ByteArray): String? {
        val data = decompress(raw) ?: return null
        return try {
            extractFromData(data)
        } catch (_: Exception) {
            null
        }
    }

    private fun decompress(raw: ByteArray): ByteArray? {
        return try {
            GZIPInputStream(ByteArrayInputStream(raw)).use { input ->
                val data = input.readNBytes(MAX_UNCOMPRESSED_BYTES + 1)
                data.takeIf { it.size <= MAX_UNCOMPRESSED_BYTES }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun extractFromData(data: ByteArray): String? {
        val cursor = NbtCursor(data)
        if (cursor.readUnsignedByte() != TAG_COMPOUND) return null
        cursor.readUtf()
        return findTopLevelId(cursor)
    }

    private fun findTopLevelId(cursor: NbtCursor): String? {
        while (cursor.hasRemaining()) {
            val tag = cursor.readUnsignedByte()
            if (tag == TAG_END) return null
            val name = cursor.readUtf()
            if (tag == TAG_STRING) {
                val value = cursor.readUtf()
                if (name == "id") return normalizeMaterial(value)
            } else {
                skipPayload(cursor, tag, depth = 0)
            }
        }
        return null
    }

    private fun normalizeMaterial(value: String): String {
        return value.removePrefix("minecraft:").uppercase(Locale.ROOT)
    }

    private fun skipPayload(cursor: NbtCursor, tag: Int, depth: Int) {
        require(depth <= MAX_DEPTH) { "NBT nesting limit exceeded" }
        val fixedSize = fixedPayloadSize(tag)
        if (fixedSize != null) {
            cursor.skip(fixedSize)
            return
        }
        skipVariablePayload(cursor, tag, depth)
    }

    private fun fixedPayloadSize(tag: Int): Int? {
        return when (tag) {
            TAG_BYTE -> 1
            TAG_SHORT -> 2
            TAG_INT, TAG_FLOAT -> 4
            TAG_LONG, TAG_DOUBLE -> 8
            else -> null
        }
    }

    private fun skipVariablePayload(cursor: NbtCursor, tag: Int, depth: Int) {
        when (tag) {
            TAG_BYTE_ARRAY -> skipArray(cursor, 1)
            TAG_STRING -> cursor.readUtf()
            TAG_LIST -> skipList(cursor, depth + 1)
            TAG_COMPOUND -> skipCompound(cursor, depth + 1)
            TAG_INT_ARRAY -> skipArray(cursor, Int.SIZE_BYTES)
            TAG_LONG_ARRAY -> skipArray(cursor, Long.SIZE_BYTES)
            else -> error("Unsupported NBT tag $tag")
        }
    }

    private fun skipArray(cursor: NbtCursor, elementBytes: Int) {
        val count = checkedCount(cursor.readInt())
        cursor.skip(Math.multiplyExact(count, elementBytes))
    }

    private fun skipList(cursor: NbtCursor, depth: Int) {
        val childTag = cursor.readUnsignedByte()
        val count = checkedCount(cursor.readInt())
        require(childTag != TAG_END || count == 0) { "Non-empty TAG_End list" }
        repeat(count) { skipPayload(cursor, childTag, depth) }
    }

    private fun skipCompound(cursor: NbtCursor, depth: Int) {
        while (cursor.hasRemaining()) {
            val tag = cursor.readUnsignedByte()
            if (tag == TAG_END) return
            cursor.readUtf()
            skipPayload(cursor, tag, depth)
        }
        error("Unterminated NBT compound")
    }

    private fun checkedCount(count: Int): Int {
        require(count in 0..MAX_COLLECTION_ENTRIES) { "Invalid NBT collection size" }
        return count
    }

    private class NbtCursor(private val data: ByteArray) {
        private var position = 0

        fun hasRemaining(): Boolean = position < data.size

        fun readUnsignedByte(): Int {
            requireAvailable(1)
            return data[position++].toInt() and 0xFF
        }

        fun readInt(): Int {
            requireAvailable(Int.SIZE_BYTES)
            val result = ((data[position].toInt() and 0xFF) shl 24) or
                ((data[position + 1].toInt() and 0xFF) shl 16) or
                ((data[position + 2].toInt() and 0xFF) shl 8) or
                (data[position + 3].toInt() and 0xFF)
            position += Int.SIZE_BYTES
            return result
        }

        fun readUtf(): String {
            val length = readUnsignedShort()
            requireAvailable(length)
            val result = String(data, position, length, Charsets.UTF_8)
            position += length
            return result
        }

        fun skip(bytes: Int) {
            require(bytes >= 0) { "Negative NBT payload size" }
            requireAvailable(bytes)
            position += bytes
        }

        private fun readUnsignedShort(): Int {
            requireAvailable(Short.SIZE_BYTES)
            val result = ((data[position].toInt() and 0xFF) shl 8) or
                (data[position + 1].toInt() and 0xFF)
            position += Short.SIZE_BYTES
            return result
        }

        private fun requireAvailable(bytes: Int) {
            require(bytes <= data.size - position) { "Truncated NBT payload" }
        }
    }
}
