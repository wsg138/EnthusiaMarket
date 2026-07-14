package net.badgersmc.em.websync

import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WebsiteSyncConfigTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `creates complete disabled defaults with 250 millisecond debounce`() {
        val result = WebsiteSyncConfigLoader(directory.toFile()).load(startup = true)
        val config = assertNotNull(result.config)
        assertFalse(config.configuredEnabled)
        assertEquals(250, config.debounce.toMillis())
        assertEquals(2000, config.maximumDebounce.toMillis())
        assertFalse(config.secretConfigured)
    }

    @Test
    fun `migration preserves unknown values and exact comma-containing secret`() {
        val secret = "alpha,beta,! punctuation  "
        directory.resolve("website-sync.yml").writeText(
            """config-version: 0
enabled: false
endpoint: "https://market-api.enthusia.info"
server-id: "enthusia-main"
sync-secret: '$secret'
unknown-key: keep
"""
        )
        val result = WebsiteSyncConfigLoader(directory.toFile()).load(startup = true)
        assertEquals(secret, assertNotNull(result.config).secret)
        val parsed = YamlConfiguration.loadConfiguration(directory.resolve("website-sync.yml").toFile())
        assertEquals("keep", parsed.getString("unknown-key"))
        assertEquals(1, parsed.getInt("config-version"))
        assertTrue(directory.resolve("website-sync.yml.bak").toFile().isFile)
        assertFalse(result.config.toString().contains(secret))
    }

    @Test
    fun `invalid reload retains prior valid runtime view`() {
        val loader = WebsiteSyncConfigLoader(directory.toFile())
        val valid = assertNotNull(loader.load(startup = true).config)
        val file = directory.resolve("website-sync.yml").toFile()
        val yaml = YamlConfiguration.loadConfiguration(file).apply { set("endpoint", "http://insecure.invalid") }
        yaml.save(file)
        assertTrue(loader.load().errors.contains("endpoint"))
        assertEquals(valid, loader.current())
    }
}
