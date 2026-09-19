package net.badgersmc.em.websync

import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Duration

data class WebsiteSyncConfig(
    val configuredEnabled: Boolean,
    val endpoint: URI,
    val serverId: String,
    val secret: String,
    val startupDelay: Duration,
    val debounce: Duration,
    val maximumDebounce: Duration,
    val reconciliation: Duration,
    val connectTimeout: Duration,
    val requestTimeout: Duration,
    val maximumConcurrentRequests: Int,
    val initialRetry: Duration,
    val maximumRetry: Duration,
    val logStatusChanges: Boolean,
    val logSuccessfulStallUpdates: Boolean,
) {
    val secretConfigured: Boolean get() = secret.isNotEmpty()
    override fun toString(): String = "WebsiteSyncConfig(enabled=$configuredEnabled, endpoint=$endpoint, " +
        "serverId=$serverId, secret=<redacted>, debounce=$debounce, maximumDebounce=$maximumDebounce)"
}

data class WebsiteSyncConfigResult(val config: WebsiteSyncConfig?, val errors: List<String>)

class WebsiteSyncConfigLoader(private val dataFolder: File) {
    private val file = File(dataFolder, "website-sync.yml")
    @Volatile private var current: WebsiteSyncConfig? = null

    fun current(): WebsiteSyncConfig? = current

    @Synchronized
    fun load(startup: Boolean = false): WebsiteSyncConfigResult {
        if (!file.exists()) createFromResource()
        return try {
            val yaml = YamlConfiguration.loadConfiguration(file)
            var changed = mergeDefaults(yaml, defaults())
            if (yaml.getInt("config-version", 0) < 1) {
                yaml.set("config-version", 1)
                changed = true
            }
            if (changed) safeSave(yaml, backup = true)
            val result = validate(yaml)
            if (result.config != null) current = result.config
            else if (startup) current = null
            result
        } catch (_: Exception) {
            if (startup) current = null
            WebsiteSyncConfigResult(null, listOf("configuration_io"))
        }
    }

    @Synchronized
    fun update(mutator: (YamlConfiguration) -> Unit): WebsiteSyncConfigResult {
        if (!file.exists()) createFromResource()
        val yaml = YamlConfiguration.loadConfiguration(file)
        mutator(yaml)
        safeSave(yaml, backup = true)
        return load()
    }

    fun setSecret(secret: String): WebsiteSyncConfigResult = update { it.set("sync-secret", secret) }
    fun clearSecret(): WebsiteSyncConfigResult = update { it.set("sync-secret", "") }
    fun setEnabled(enabled: Boolean): WebsiteSyncConfigResult = update { it.set("enabled", enabled) }

    private fun createFromResource() {
        dataFolder.mkdirs()
        val stream = WebsiteSyncConfigLoader::class.java.classLoader.getResourceAsStream("website-sync.yml")
            ?: error("Bundled website-sync.yml is missing")
        val temp = File(dataFolder, ".website-sync.yml.tmp")
        stream.use { input -> temp.outputStream().use { input.copyTo(it) } }
        replace(temp)
    }

    private fun defaults(): YamlConfiguration {
        val input = WebsiteSyncConfigLoader::class.java.classLoader.getResourceAsStream("website-sync.yml")
            ?: error("Bundled website-sync.yml is missing")
        return input.reader().use { reader -> YamlConfiguration().apply { load(reader) } }
    }

    private fun mergeDefaults(target: ConfigurationSection, source: ConfigurationSection): Boolean {
        var changed = false
        for (key in source.getKeys(false)) {
            val sourceValue = source.get(key)
            val targetSection = target.getConfigurationSection(key)
            val sourceSection = source.getConfigurationSection(key)
            if (sourceSection != null) {
                val section = targetSection ?: target.createSection(key).also { changed = true }
                if (mergeDefaults(section, sourceSection)) changed = true
            } else if (!target.contains(key)) {
                target.set(key, sourceValue)
                changed = true
            }
        }
        return changed
    }

    private fun safeSave(yaml: YamlConfiguration, backup: Boolean) {
        if (backup && file.exists()) {
            Files.copy(file.toPath(), File(dataFolder, "website-sync.yml.bak").toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        val temp = File(dataFolder, ".website-sync.yml.tmp")
        yaml.save(temp)
        replace(temp)
    }

    private fun replace(temp: File) {
        try {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun validate(yaml: YamlConfiguration): WebsiteSyncConfigResult =
        WebsiteSyncConfigValidator(yaml).validate()
}

private class WebsiteSyncConfigValidator(private val yaml: YamlConfiguration) {
    private val errors = mutableListOf<String>()

    fun validate(): WebsiteSyncConfigResult {
        validateVersion()
        val endpoint = endpoint()
        val serverId = serverId()
        val timing = timing()
        val http = http()
        val retry = retry()
        validateRelativeBounds(timing, retry)
        return result(endpoint, serverId, timing, http, retry)
    }

    private fun validateVersion() {
        if (yaml.getInt("config-version") != 1) errors += "config_version"
    }

    private fun endpoint(): URI? {
        val endpoint = runCatching { URI(yaml.getString("endpoint") ?: "") }.getOrNull()
        if (!isSecureEndpoint(endpoint)) errors += "endpoint"
        return endpoint
    }

    private fun isSecureEndpoint(endpoint: URI?): Boolean =
        endpoint != null && endpoint.scheme == "https" && !endpoint.host.isNullOrBlank()

    private fun serverId(): String {
        val serverId = yaml.getString("server-id") ?: ""
        if (serverId != "enthusia-main") errors += "server_id"
        return serverId
    }

    private fun timing(): TimingValues = TimingValues(
        startup = bounded("timing.startup-delay-seconds", 0, 3600),
        debounce = bounded("timing.stall-debounce-milliseconds", 50, 30_000),
        maximumDebounce = bounded("timing.maximum-debounce-milliseconds", 50, 30_000),
        reconciliation = bounded("timing.reconciliation-minutes", 1, 1440),
    )

    private fun http(): HttpValues = HttpValues(
        connect = bounded("http.connect-timeout-seconds", 1, 120),
        request = bounded("http.request-timeout-seconds", 1, 300),
        concurrent = bounded("http.maximum-concurrent-requests", 1, 1),
    )

    private fun retry(): RetryValues = RetryValues(
        initial = bounded("retry.initial-delay-seconds", 1, 3600),
        maximum = bounded("retry.maximum-delay-seconds", 1, 86_400),
    )

    private fun bounded(path: String, min: Int, max: Int): Int {
        val value = yaml.getInt(path, Int.MIN_VALUE)
        if (value !in min..max) errors += path.replace('.', '_')
        return value
    }

    private fun validateRelativeBounds(timing: TimingValues, retry: RetryValues) {
        if (timing.maximumDebounce < timing.debounce) errors += "maximum_debounce"
        if (retry.maximum < retry.initial) errors += "maximum_retry"
    }

    private fun result(
        endpoint: URI?,
        serverId: String,
        timing: TimingValues,
        http: HttpValues,
        retry: RetryValues,
    ): WebsiteSyncConfigResult {
        if (errors.isNotEmpty() || endpoint == null) return WebsiteSyncConfigResult(null, errors.distinct())
        return WebsiteSyncConfigResult(
            WebsiteSyncConfig(
                configuredEnabled = yaml.getBoolean("enabled", false),
                endpoint = endpoint,
                serverId = serverId,
                secret = yaml.getString("sync-secret", "") ?: "",
                startupDelay = Duration.ofSeconds(timing.startup.toLong()),
                debounce = Duration.ofMillis(timing.debounce.toLong()),
                maximumDebounce = Duration.ofMillis(timing.maximumDebounce.toLong()),
                reconciliation = Duration.ofMinutes(timing.reconciliation.toLong()),
                connectTimeout = Duration.ofSeconds(http.connect.toLong()),
                requestTimeout = Duration.ofSeconds(http.request.toLong()),
                maximumConcurrentRequests = http.concurrent,
                initialRetry = Duration.ofSeconds(retry.initial.toLong()),
                maximumRetry = Duration.ofSeconds(retry.maximum.toLong()),
                logStatusChanges = yaml.getBoolean("logging.status-changes", true),
                logSuccessfulStallUpdates = yaml.getBoolean("logging.successful-stall-updates", false),
            ),
            emptyList(),
        )
    }
}

private data class TimingValues(
    val startup: Int,
    val debounce: Int,
    val maximumDebounce: Int,
    val reconciliation: Int,
)

private data class HttpValues(val connect: Int, val request: Int, val concurrent: Int)

private data class RetryValues(val initial: Int, val maximum: Int)
