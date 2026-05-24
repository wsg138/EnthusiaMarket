# LumaGuilds Public API — Snapshot

**Source:** LumaGuilds plugin source (BadgersMC/LumaGuilds, local build)
**Status:** Stub — `LumaGuildsGuildProvider` returns `TODO()` for all methods
**Snapshot date:** 2026-05-24

## Expected Types (from EnthusiaMarket's GuildProvider port)

EnthusiaMarket defines the following port in `net.badgersmc.em.domain.ports.GuildProvider`:

```kotlin
interface GuildProvider {
    data class GuildRef(val id: String, val name: String)

    fun guildOf(player: UUID): GuildRef?
    fun guildById(id: String): GuildRef?
    fun isMember(player: UUID, guildId: String): Boolean
    fun hasPermission(player: UUID, guildId: String, node: String): Boolean

    fun bankBalance(guildId: String): Long
    fun bankWithdraw(guildId: String, amount: Long): Boolean
    fun bankDeposit(guildId: String, amount: Long): Boolean

    /** Register a callback invoked when a guild is dissolved. */
    fun onDissolved(handler: (guildId: String) -> Unit)
}
```

## Stub Implementation (current)

```kotlin
// infrastructure/lumaguilds/LumaGuildsGuildProvider.kt
class LumaGuildsGuildProvider : GuildProvider {
    override fun guildOf(player: UUID): GuildRef? = TODO("Plan 5")
    override fun guildById(id: String): GuildRef? = TODO("Plan 5")
    override fun isMember(player: UUID, guildId: String): Boolean = TODO("Plan 5")
    override fun hasPermission(player: UUID, guildId: String, node: String): Boolean = TODO("Plan 5")
    override fun bankBalance(guildId: String): Long = TODO("Plan 5")
    override fun bankWithdraw(guildId: String, amount: Long): Boolean = TODO("Plan 5")
    override fun bankDeposit(guildId: String, amount: Long): Boolean = TODO("Plan 5")
    override fun onDissolved(handler: (String) -> Unit) { dissolveHandlers.add(handler) }
}
```

## Configuration Dependencies (from config.yml)

| Key | Default | Relevance |
|---|---|---|
| `lumaguilds.enabled` | `true` | Disable → skip guild DI wiring |
| `lumaguilds.manage-rank` | `officer` | Rank ID for `Stall.canManage()` |
| `lumaguilds.pay-from` | `bank` | `bank` = guild bank; `leader` = leader's personal balance |

## Integration Plan (M4, TDD-40/TDD-41)

1. Obtain LumaGuilds plugin JAR and inspect actual API surface.
2. Replace `TODO()` stubs with real `net.lumaguilds.api.*` calls.
3. Add TDD-40 test: guild member with required rank passes `Stall.canManage()`; lower rank fails.
4. Wire `guildProvider` in `di/Modules.kt` when `lumaguilds.enabled == true`.

## Evidence

- src/main/kotlin/.../infrastructure/lumaguilds/LumaGuildsGuildProvider.kt (stub)
- src/main/kotlin/.../domain/ports/GuildProvider.kt (port interface)
- src/main/resources/config.yml (lumaguilds section)
- Integration deferred to M4 (TDD-40, TDD-41)

## Open Questions

- What is the exact LumaGuilds API class/method for player→guild lookup?
- Does LumaGuilds expose a permission node system compatible with `hasPermission(uuid, guildId, node)`?
- Is the guild bank API synchronous or asynchronous?

These must be answered by inspecting the LumaGuilds plugin Javadocs or source before M4.
