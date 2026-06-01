package net.badgersmc.em.application

import net.badgersmc.em.domain.stall.EntityLimitGroup
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

/**
 * Loads entitylimits.yml (REQ-220) into a map of region-kind name ->
 * [EntityLimitGroup]. The `_total` key maps to the group total; every
 * other key is a lower-case EntityType cap. -1 means unlimited.
 */
object EntityLimitConfig {

    private const val TOTAL_KEY = "_total"

    /** Parse a YAML string (used by tests and the file loader). */
    fun parse(yaml: String): Map<String, EntityLimitGroup> {
        val config = YamlConfiguration()
        config.loadFromString(yaml)
        val out = LinkedHashMap<String, EntityLimitGroup>()
        for (kind in config.getKeys(false)) {
            val section = config.getConfigurationSection(kind) ?: continue
            var total = -1
            val perType = LinkedHashMap<String, Int>()
            for (key in section.getKeys(false)) {
                val value = section.getInt(key)
                if (key == TOTAL_KEY) total = value else perType[key.lowercase()] = value
            }
            out[kind] = EntityLimitGroup(total, perType)
        }
        return out
    }

    /** Load from a file on disk. */
    fun load(file: File): Map<String, EntityLimitGroup> = parse(file.readText())

    /**
     * Resolve a group for a given region [kind]. Falls back to the
     * "default" group when it exists; otherwise returns an unlimited
     * group (total=-1, no per-type caps).
     */
    fun groupFor(groups: Map<String, EntityLimitGroup>, kind: String): EntityLimitGroup {
        groups[kind]?.let { return it }
        groups["default"]?.let { return it }
        return EntityLimitGroup(total = -1, perType = emptyMap())
    }
}
