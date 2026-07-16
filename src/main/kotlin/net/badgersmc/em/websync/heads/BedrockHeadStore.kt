package net.badgersmc.em.websync.heads

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import net.badgersmc.em.websync.DeliveryOutcome
import net.badgersmc.em.websync.WebsiteSyncConfig
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(it) }

private fun publicUrl(hash: String) = "https://market-api.enthusia.info/v1/player-heads/$hash.png"

private const val MAX_HEAD_ENTRIES = 256
private val HEAD_HASH = Regex("^[0-9a-f]{64}$")
private data class Published(val hash: String, val url: String, val capturedAt: Long)
private data class Pending(val hash: String, val capturedAt: Long, val attempts: Int, val nextAttemptAt: Long)
private data class HeadIndex(
    val published: MutableMap<String, Published> = linkedMapOf(),
    val pending: MutableMap<String, Pending> = linkedMapOf(),
)

private fun validPublished(id: String, entry: Published): Boolean = runCatching {
    UUID.fromString(id)
    HEAD_HASH.matches(entry.hash) && entry.url == publicUrl(entry.hash) && entry.capturedAt >= 0
}.getOrDefault(false)

private fun validPending(id: String, entry: Pending): Boolean = runCatching {
    UUID.fromString(id)
    HEAD_HASH.matches(entry.hash) && entry.capturedAt >= 0 && entry.attempts >= 0 && entry.nextAttemptAt >= 0
}.getOrDefault(false)

private fun trimLoaded(value: HeadIndex): Boolean {
    var trimmed = false
    while (value.published.size + value.pending.size > MAX_HEAD_ENTRIES) {
        val oldest = value.published.minByOrNull { it.value.capturedAt }
        if (oldest != null) value.published.remove(oldest.key)
        else value.pending.minByOrNull { it.value.capturedAt }?.let { value.pending.remove(it.key) } ?: break
        trimmed = true
    }
    return trimmed
}

private fun validFileBounds(file: File): Boolean =
    file.isFile && file.length() in 1..BedrockHeadRenderer.MAX_PNG_BYTES.toLong()

private fun validPngFile(file: File, hash: String): Boolean {
    val bytes = runCatching { file.readBytes() }.getOrNull() ?: return false
    if (sha256(bytes) != hash) return false
    val image = runCatching { javax.imageio.ImageIO.read(file) }.getOrNull() ?: return false
    return validHeadImage(image)
}

private fun validHeadImage(image: java.awt.image.BufferedImage): Boolean {
    if (image.width != BedrockHeadRenderer.OUTPUT_SIZE) return false
    if (image.height != BedrockHeadRenderer.OUTPUT_SIZE) return false
    return image.colorModel.hasAlpha()
}

private fun validPendingFile(pendingDirectory: File, pending: Pending): Boolean {
    val file = File(pendingDirectory, "${pending.hash}.png")
    return validFileBounds(file) && validPngFile(file, pending.hash)
}

private fun atomicBytes(target: File, bytes: ByteArray) {
    val temp = File(target.parentFile, ".${target.name}.tmp")
    temp.writeBytes(bytes)
    replace(temp, target)
}

private fun replace(temp: File, target: File) {
    try {
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

data class BedrockHeadStatus(
    val captured: Int,
    val pending: Int,
    val lastSuccessAt: Long?,
    val lastError: String?,
)

/** Durable, bounded cache for finished public heads. All mutations run on one worker. */
class BedrockHeadStore(
    dataFolder: File,
    private val config: () -> WebsiteSyncConfig?,
    private val uploader: (WebsiteSyncConfig, UUID, String, ByteArray) -> DeliveryOutcome,
    private val published: (UUID) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) : PublishedHeadLookup, AutoCloseable {
    private enum class CaptureAction { PUBLISHED, UPLOAD, NONE }

    private val root = File(dataFolder, "website-heads")
    private val pendingDirectory = File(root, "pending")
    private val indexFile = File(root, "index.json")
    private val lock = Any()
    private val gson = Gson()
    private var lastError: String? = null
    private var lastSuccessAt: Long? = null
    private var index = load()
    private val executor = ThreadPoolExecutor(
        1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(64),
        { task -> Thread(task, "EnthusiaMarket-BedrockHeads").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )

    init {
        root.mkdirs()
        pendingDirectory.mkdirs()
        val invalid = index.pending.filterValues { !validPendingFile(pendingDirectory, it) }.keys
        if (invalid.isNotEmpty()) {
            invalid.forEach(index.pending::remove)
            persist()
            lastError = "pending_file_invalid"
        } else if (lastError == "index_invalid") {
            persist()
        }
    }

    override fun url(playerId: UUID): String? {
        return synchronized(lock) {
            val entry = index.published[playerId.toString()] ?: return@synchronized null
            entry.url.takeIf { it == publicUrl(entry.hash) }
        }
    }

    fun capture(playerId: UUID, copiedSkin: ByteArray) {
        submit { captureInBackground(playerId, copiedSkin) }
    }

    private fun captureInBackground(playerId: UUID, copiedSkin: ByteArray) {
        try {
            val png = BedrockHeadRenderer.render(copiedSkin)
            val hash = sha256(png)
            val action = synchronized(lock) { recordCapture(playerId, hash, png, clock()) }
            when (action) {
                CaptureAction.PUBLISHED -> published(playerId)
                CaptureAction.UPLOAD -> upload(playerId)
                CaptureAction.NONE -> Unit
            }
        } catch (_: IllegalArgumentException) {
            setError("invalid_skin")
        } catch (_: Exception) {
            setError("capture_failure")
        }
    }

    private fun recordCapture(playerId: UUID, hash: String, png: ByteArray, now: Long): CaptureAction {
        existingCapture(playerId, hash, now)?.let { return it }
        pendingDirectory.mkdirs()
        atomicBytes(File(pendingDirectory, "$hash.png"), png)
        val uploadRequired = index.pending.values.none { it.hash == hash }
        val previous = index.pending.put(playerId.toString(), Pending(hash, now, 0, now))
        removeOrphan(previous, hash)
        trim()
        persist()
        lastError = null
        return if (uploadRequired) CaptureAction.UPLOAD else CaptureAction.NONE
    }

    private fun existingCapture(playerId: UUID, hash: String, now: Long): CaptureAction? {
        val key = playerId.toString()
        if (index.published[key]?.hash == hash) {
            index.published[key] = Published(hash, publicUrl(hash), now)
            index.pending.remove(key)
            persist()
            return CaptureAction.PUBLISHED
        }
        return CaptureAction.NONE.takeIf { index.pending[key]?.hash == hash }
    }

    private fun removeOrphan(previous: Pending?, replacementHash: String) {
        if (previous == null || previous.hash == replacementHash || index.pending.values.any { it.hash == previous.hash }) return
        runCatching { Files.deleteIfExists(File(pendingDirectory, "${previous.hash}.png").toPath()) }
    }

    fun retryPending() {
        val cfg = config()
        if (cfg?.configuredEnabled != true || !cfg.secretConfigured) return
        submit {
            val due = synchronized(lock) {
                index.pending.entries.filter { it.value.nextAttemptAt <= clock() }
                    .mapNotNull { runCatching { UUID.fromString(it.key) }.getOrNull() }
            }
            due.forEach(::upload)
        }
    }

    fun status(): BedrockHeadStatus {
        return synchronized(lock) {
            BedrockHeadStatus(index.published.size, index.pending.size, lastSuccessAt, lastError)
        }
    }

    private fun upload(playerId: UUID) {
        val cfg = config()
        if (cfg?.configuredEnabled != true || !cfg.secretConfigured) return
        val pending = synchronized(lock) { index.pending[playerId.toString()] } ?: return
        val png = pendingBytes(pending) ?: return setError("pending_file_invalid")
        handleDelivery(playerId, pending, uploader(cfg, playerId, pending.hash, png))
    }

    private fun handleDelivery(playerId: UUID, pending: Pending, outcome: DeliveryOutcome) {
        when (outcome) {
            DeliveryOutcome.Success -> complete(pending)
            is DeliveryOutcome.Retry -> defer(playerId, pending, "upload_retry")
            is DeliveryOutcome.Pause -> defer(playerId, pending, "upload_rejected")
            is DeliveryOutcome.Reconcile -> defer(playerId, pending, "upload_rejected")
        }
    }

    private fun pendingBytes(pending: Pending): ByteArray? {
        val bytes = runCatching { File(pendingDirectory, "${pending.hash}.png").readBytes() }.getOrNull() ?: return null
        if (bytes.size !in 1..BedrockHeadRenderer.MAX_PNG_BYTES || sha256(bytes) != pending.hash) return null
        return bytes
    }

    private fun complete(pending: Pending) {
        val publishedPlayers: List<UUID>
        synchronized(lock) {
            publishedPlayers = index.pending.filterValues { it.hash == pending.hash }.mapNotNull { (id, entry) ->
                runCatching { UUID.fromString(id) }.getOrNull()?.also {
                    index.published[id] = Published(entry.hash, publicUrl(entry.hash), entry.capturedAt)
                }
            }
            index.pending.entries.removeIf { it.value.hash == pending.hash }
            trim()
            persist()
            if (index.pending.values.none { it.hash == pending.hash }) {
                runCatching { Files.deleteIfExists(File(pendingDirectory, "${pending.hash}.png").toPath()) }
            }
            lastSuccessAt = clock()
            lastError = null
        }
        publishedPlayers.forEach(published)
    }

    private fun defer(playerId: UUID, pending: Pending, category: String) {
        synchronized(lock) {
            val attempts = (pending.attempts + 1).coerceAtMost(MAX_RETRY_EXPONENT)
            val delay = INITIAL_RETRY_MILLIS * (1L shl attempts).coerceAtMost(MAX_RETRY_MULTIPLIER)
            index.pending[playerId.toString()] = pending.copy(attempts = attempts, nextAttemptAt = clock() + delay)
            persist()
            lastError = category
        }
    }

    private fun load(): HeadIndex {
        if (!indexFile.isFile) return HeadIndex()
        return runCatching {
            val type = object : TypeToken<HeadIndex>() {}.type
            gson.fromJson<HeadIndex>(indexFile.readText(), type).also(::removeMalformedEntries)
        }.getOrElse { HeadIndex().also { lastError = "index_invalid" } }
    }

    private fun removeMalformedEntries(value: HeadIndex) {
        val publishedRemoved = value.published.entries.removeIf { !validPublished(it.key, it.value) }
        val pendingRemoved = value.pending.entries.removeIf { !validPending(it.key, it.value) }
        val trimmed = trimLoaded(value)
        if (publishedRemoved || pendingRemoved || trimmed) lastError = "index_invalid"
    }

    private fun trim() {
        while (index.published.size + index.pending.size > MAX_HEAD_ENTRIES) {
            if (!evictOldest()) break
        }
    }

    private fun evictOldest(): Boolean {
        val publishedOldest = index.published.minByOrNull { it.value.capturedAt }
        if (publishedOldest != null) return index.published.remove(publishedOldest.key) != null
        val pendingOldest = index.pending.minByOrNull { it.value.capturedAt } ?: return false
        val removed = index.pending.remove(pendingOldest.key) ?: return false
        if (index.pending.values.none { it.hash == removed.hash }) {
            runCatching { Files.deleteIfExists(File(pendingDirectory, "${removed.hash}.png").toPath()) }
        }
        return true
    }

    private fun persist() {
        root.mkdirs()
        val temp = File(root, ".index.json.tmp")
        temp.writeText(gson.toJson(index))
        replace(temp, indexFile)
    }

    private fun submit(block: () -> Unit) {
        runCatching { executor.execute(block) }.onFailure { synchronized(lock) { lastError = "executor_saturated" } }
    }

    private fun setError(category: String) {
        synchronized(lock) { lastError = category }
    }

    override fun close() {
        executor.shutdown()
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow()
        } catch (_: InterruptedException) {
            executor.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }

    private companion object {
        const val INITIAL_RETRY_MILLIS = 5_000L
        const val MAX_RETRY_EXPONENT = 8
        const val MAX_RETRY_MULTIPLIER = 256L
    }
}
