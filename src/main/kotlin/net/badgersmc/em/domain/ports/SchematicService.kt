package net.badgersmc.em.domain.ports

/**
 * Port for capturing and restoring the geometry of a stall's WorldGuard region
 * as a WorldEdit schematic (REQ-270..274).
 *
 * Kept free of WorldEdit/FAWE and Bukkit types so the application layer can
 * orchestrate snapshot-on-claim / restore-on-unclaim without depending on the
 * server runtime. The infrastructure adapter
 * (`WorldEditSchematicAdapter`) selects FAWE when present and falls back to
 * vanilla WorldEdit (REQ-272).
 */
interface SchematicService {

    sealed interface Result {
        data object Success : Result
        /** Snapshots disabled via `schematics.enabled: false` (REQ-273) — treated as success by callers. */
        data object Disabled : Result
        data class Failure(val cause: Throwable) : Result
    }

    /**
     * Capture the current geometry of WorldGuard region [regionId] in [world]
     * to `<directory>/<stallId>.schem`. Idempotent per stall lifetime: if a
     * snapshot already exists it is NOT overwritten (REQ-270 "for the first
     * time"), returning [Result.Success].
     */
    fun capture(stallId: String, world: String, regionId: String): Result

    /**
     * Paste the stored snapshot for [stallId] over region [regionId] in [world],
     * restoring the original geometry (REQ-271). Pastes asynchronously when FAWE
     * is present, synchronously otherwise (REQ-272).
     */
    fun restore(stallId: String, world: String, regionId: String): Result

    /** No-op implementation used when snapshots are globally disabled or in tests. */
    object Disabled : SchematicService {
        override fun capture(stallId: String, world: String, regionId: String): Result = Result.Disabled
        override fun restore(stallId: String, world: String, regionId: String): Result = Result.Disabled
    }
}
