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

data class BedrockHeadStatus(
    val captured: Int,
    val pending: Int,
    val lastSuccessAt: Long?,
    val lastError: String?,
)

fun interface PublishedHeadLookup {
    fun url(playerId: UUID): String?
}

/** Durable, bounded cache for finished public heads. All mutations run on one worker. */
class BedrockHeadStore(
    dataFolder: File,
    private val config: () -> WebsiteSyncConfig?,
    private val uploader: (WebsiteSyncConfig, UUID, String, ByteArray) -> DeliveryOutcome,
    private val published: (UUID) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) : PublishedHeadLookup, AutoCloseable {
    private data class Published(val hash: String, val url: String, val capturedAt: Long)
    private data class Pending(val hash: String, val capturedAt: Long, val attempts: Int, val nextAttemptAt: Long)
    private data class Index(
        val published: MutableMap<String, Published> = linkedMapOf(),
        val pending: MutableMap<String, Pending> = linkedMapOf(),
    )

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
        val invalid = index.pending.filterValues { !validPendingFile(it) }.keys
        if (invalid.isNotEmpty()) {
            invalid.forEach(index.pending::remove)
            persist()
            lastError = "pending_file_invalid"
        } else if (lastError == "index_invalid") {
            persist()
        }
    }

    override fun url(playerId: UUID): String? = synchronized(lock) {
        val entry = index.published[playerId.toString()] ?: return@synchronized null
        entry.url.takeIf { it == publicUrl(entry.hash) }
    }

    fun capture(playerId: UUID, copiedSkin: ByteArray) {
        submit {
            try {
                val png = BedrockHeadRenderer.render(copiedSkin)
                val hash = sha256(png)
                val now = clock()
                var alreadyPublished = false
                var uploadRequired = true
                synchronized(lock) {
                    val key = playerId.toString()
                    if (index.published[key]?.hash == hash) {
                        index.published[key] = Published(hash, publicUrl(hash), now)
                        index.pending.remove(key)
                        persist()
                        alreadyPublished = true
                        return@synchronized
                    }
                    if (index.pending[key]?.hash == hash) {
                        uploadRequired = false
                        return@synchronized
                    }
                    pendingDirectory.mkdirs()
                    atomicBytes(File(pendingDirectory, "$hash.png"), png)
                    if (index.pending.values.any { it.hash == hash }) uploadRequired = false
                    val previous = index.pending.put(key, Pending(hash, now, 0, now))
                    if (previous != null && previous.hash != hash && index.pending.values.none { it.hash == previous.hash }) {
                        runCatching { Files.deleteIfExists(File(pendingDirectory, "${previous.hash}.png").toPath()) }
                    }
                    trim()
                    persist()
                    lastError = null
                }
                if (alreadyPublished) published(playerId) else if (uploadRequired) upload(playerId)
            } catch (_: IllegalArgumentException) {
                synchronized(lock) { lastError = "invalid_skin" }
            } catch (_: Exception) {
                synchronized(lock) { lastError = "capture_failure" }
            }
        }
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

    fun status(): BedrockHeadStatus = synchronized(lock) {
        BedrockHeadStatus(index.published.size, index.pending.size, lastSuccessAt, lastError)
    }

    private fun upload(playerId: UUID) {
        val cfg = config()
        if (cfg?.configuredEnabled != true || !cfg.secretConfigured) return
        val pending = synchronized(lock) { index.pending[playerId.toString()] } ?: return
        val file = File(pendingDirectory, "${pending.hash}.png")
        val png = runCatching { file.readBytes() }.getOrNull()
        if (png == null || png.size !in 1..BedrockHeadRenderer.MAX_PNG_BYTES || sha256(png) != pending.hash) {
            synchronized(lock) { lastError = "pending_file_invalid" }
            return
        }
        when (uploader(cfg, playerId, pending.hash, png)) {
            DeliveryOutcome.Success -> complete(pending)
            is DeliveryOutcome.Retry -> defer(playerId, pending, "upload_retry")
            is DeliveryOutcome.Pause -> defer(playerId, pending, "upload_rejected")
            is DeliveryOutcome.Reconcile -> defer(playerId, pending, "upload_rejected")
        }
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

    private fun load(): Index {
        if (!indexFile.isFile) return Index()
        return runCatching {
            val type = object : TypeToken<Index>() {}.type
            gson.fromJson<Index>(indexFile.readText(), type).also(::removeMalformedEntries)
        }.getOrElse { Index().also { lastError = "index_invalid" } }
    }

    private fun removeMalformedEntries(value: Index) {
        var removed = false
        value.published.entries.removeIf { entry ->
            val valid = runCatching {
                UUID.fromString(entry.key)
                HASH.matches(entry.value.hash) && entry.value.url == publicUrl(entry.value.hash) &&
                    entry.value.capturedAt >= 0
            }.getOrDefault(false)
            if (!valid) removed = true
            !valid
        }
        value.pending.entries.removeIf { entry ->
            val valid = runCatching {
                UUID.fromString(entry.key)
                HASH.matches(entry.value.hash) && entry.value.capturedAt >= 0 && entry.value.attempts >= 0 &&
                    entry.value.nextAttemptAt >= 0
            }.getOrDefault(false)
            if (!valid) removed = true
            !valid
        }
        while (value.published.size + value.pending.size > MAX_ENTRIES) {
            val oldest = value.published.minByOrNull { it.value.capturedAt }
            if (oldest != null) value.published.remove(oldest.key) else {
                value.pending.minByOrNull { it.value.capturedAt }?.let { value.pending.remove(it.key) } ?: break
            }
            removed = true
        }
        if (removed) lastError = "index_invalid"
    }

    private fun validPendingFile(pending: Pending): Boolean {
        val file = File(pendingDirectory, "${pending.hash}.png")
        if (!file.isFile || file.length() !in 1..BedrockHeadRenderer.MAX_PNG_BYTES.toLong()) return false
        return runCatching {
            val bytes = file.readBytes()
            if (sha256(bytes) != pending.hash) return@runCatching false
            val image = javax.imageio.ImageIO.read(file) ?: return@runCatching false
            image.width == BedrockHeadRenderer.OUTPUT_SIZE && image.height == BedrockHeadRenderer.OUTPUT_SIZE &&
                image.colorModel.hasAlpha()
        }.getOrDefault(false)
    }

    private fun trim() {
        while (index.published.size + index.pending.size > MAX_ENTRIES) {
            val publishedOldest = index.published.minByOrNull { it.value.capturedAt }
            if (publishedOldest != null) {
                index.published.remove(publishedOldest.key)
            } else {
                val pendingOldest = index.pending.minByOrNull { it.value.capturedAt } ?: break
                val removed = index.pending.remove(pendingOldest.key) ?: continue
                if (index.pending.values.none { it.hash == removed.hash }) {
                    runCatching { Files.deleteIfExists(File(pendingDirectory, "${removed.hash}.png").toPath()) }
                }
            }
        }
    }

    private fun persist() {
        root.mkdirs()
        val temp = File(root, ".index.json.tmp")
        temp.writeText(gson.toJson(index))
        replace(temp, indexFile)
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

    private fun submit(block: () -> Unit) {
        runCatching { executor.execute(block) }.onFailure { synchronized(lock) { lastError = "executor_saturated" } }
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
        const val MAX_ENTRIES = 256
        const val INITIAL_RETRY_MILLIS = 5_000L
        const val MAX_RETRY_EXPONENT = 8
        const val MAX_RETRY_MULTIPLIER = 256L
        val HASH = Regex("^[0-9a-f]{64}$")

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

        fun publicUrl(hash: String) = "https://market-api.enthusia.info/v1/player-heads/$hash.png"
    }
}
