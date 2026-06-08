# Guild Policy Notifications (Tariff/Embargo Awareness) — Design

**Date:** 2026-06-08
**Status:** Approved (brainstorming complete)
**Owner:** BadgersMC
**Plugin:** EnthusiaMarket (EM)

## 0. Summary

Players currently have no way to *see* guild tariffs/embargoes — economic warfare they can't perceive just feels like broken/random pricing. This adds two player-facing notifications on top of the Guild Trade Policies engine (PR-A):

1. **Set-announcement** — a server-wide broadcast when a guild sets, changes, or lifts a tariff/embargo.
2. **Entry warning** — a personal message when a player enters a guild-owned stall where their guild is tariffed/embargoed, with the numbers.

Both are `LangService`-templated (MiniMessage `<token>`, fully configurable) and individually toggleable in config.

This is **PR-C**, sequenced after PR-A (the engine) merges. It is independent of PR-B (the management GUI) but touches the same `GuildTradePolicyService`, so the two ship one at a time.

## 1. Decisions (settled in brainstorming)

| Question | Decision |
|---|---|
| Set-announcement audience | **Server-wide broadcast** |
| Announce on lift/clear too | **Yes** (set tariff, set embargo, and clear all broadcast) |
| Announce abuse guard | Config toggle **+ a per-actor cooldown** (default 30s) so rapid re-setting can't spam chat |
| Entry-warning frequency | **Every entry** — fires on each genuine region change *into* an affected stall (tracking last region per player; standing still / intra-region movement does not re-fire) |
| Entry-warning audience | **The entering player only** |
| Who is warned | Only when the mover's guild has a policy from the stall's owning guild. Silent for solo players, own-guild members, and policy-free stalls |

## 2. Architecture

Hexagonal/SPEAR. The engine (PR-A) stays Bukkit-broadcast-free; notifications live in infrastructure listeners, decoupled from the service via a domain event.

### New units
- **`events/GuildTradePolicyChangedEvent`** — a Bukkit `Event` carrying `ownerGuildId: String`, `targetGuildId: String`, `kind: PolicyKind?` (null when cleared), `ratePct: Int`, `action: Action { SET, CLEARED }`. Standard `HandlerList` shape (mirror `SchematicCaptureFailedEvent`).
- **`infrastructure/listeners/GuildPolicyAnnounceListener`** (`@Component`) — `@EventHandler` on `GuildTradePolicyChangedEvent`. Resolves both guild display names via `guildProvider.guildById(id)?.name` (falls back to the raw id), applies the config toggle + per-actor cooldown, and `Bukkit.broadcast(...)` the rendered `LangService` line.
- **`infrastructure/listeners/GuildShopPolicyEntryListener`** (`@Component`) — `@EventHandler(ignoreCancelled = true)` on `PlayerMoveEvent`. Hot-path-cheap: returns immediately unless `event.from.block != event.to.block`; resolves the new region via `RegionProvider.regionAt(...)`, compares to the player's `lastRegion` (in-memory `MutableMap<UUID, String?>`), and only acts when the region changed *and* the new region is a GUILD-owned stall with a policy toward the mover's guild.

### Changed units
- **`application/GuildTradePolicyService`** — (a) the three management methods (`setTariff`, `setEmbargo`, `clear`) fire `GuildTradePolicyChangedEvent` on success via the existing null-safe `Bukkit.getServer()?.pluginManager?.callEvent(...)` pattern; (b) add a direction-free lookup `policyToward(ownerGuildId: String, buyer: UUID): GuildTradePolicy?` (guild resolution + `repo.find`) for the entry warning. Returns null for solo/own-guild/no-policy.
- **`config/EnthusiaMarketConfig`** — new `guildPolicy` block: `announceEnabled: Boolean = true`, `entryWarningEnabled: Boolean = true`, `announceCooldownSeconds: Int = 30`.
- **`resources/lang/en_US.yml`** — keys: `guildpolicy.announce.tariff`, `guildpolicy.announce.embargo`, `guildpolicy.announce.cleared`, `guildpolicy.entry.tariff`, `guildpolicy.entry.embargo` (tokens: `<owner>`, `<target>`, `<rate>`).
- **Listener registration** — both listeners are `@Component` Bukkit `Listener`s, auto-registered by the Phase-6 `registerNexusListeners` scan (same as `SignPlaceListener`).

## 3. Data flow

### Set-announcement
```text
MANAGE_SHOPS member sets a 20% tariff via GUI/command
  → GuildTradePolicyService.setTariff(...)  (PR-A)
      → on Ok: callEvent(GuildTradePolicyChangedEvent(ownerGuild, targetGuild, TARIFF, 20, SET))
  → GuildPolicyAnnounceListener
      → if !config.guildPolicy.announceEnabled → return
      → if actor on cooldown → return (cooldown keyed by the acting guild/player)
      → owner = guildById(ownerGuild)?.name ?: ownerGuild ; target = guildById(targetGuild)?.name ?: targetGuild
      → Bukkit.broadcast(lang.msg("guildpolicy.announce.tariff", "owner" to owner, "target" to target, "rate" to 20))
```
Clear → `action = CLEARED`, `kind = null` → `guildpolicy.announce.cleared`.

### Entry warning
```text
player moves, from.block != to.block
  → region = RegionProvider.regionAt(world, x, y, z)
  → if region == lastRegion[player] → return ; else lastRegion[player] = region
  → if region == null → return
  → stall = stalls.findByRegion(world, region) ; if stall == null or owner.type != GUILD → return
  → policy = policyService.policyToward(stall.owner.id, player)   // null = solo/own-guild/no policy → return
  → if !config.guildPolicy.entryWarningEnabled → return
  → owner = guildById(stall.owner.id)?.name ?: stall.owner.id
  → policy.kind == TARIFF → player.sendMessage(lang.msg("guildpolicy.entry.tariff", "owner" to owner, "rate" to policy.ratePct))
  → policy.kind == EMBARGO → player.sendMessage(lang.msg("guildpolicy.entry.embargo", "owner" to owner))
```

## 4. Error handling
- Unknown / disbanded guild name → fall back to the raw guild id in the message (never crash). A disbanded owner's policies are already cleaned up by `GuildDissolutionService` (PR-A), so a live policy implies a live owner in the normal case.
- `PlayerMoveEvent` never does WG/DB work unless the player changed block **and** region — keeps the hot path O(1) on most moves.
- Player relog → `lastRegion` entry absent → next entry re-warns (intended). Clean up the map on `PlayerQuitEvent` to avoid a slow leak.
- Broadcast/lang failure → caught + logged; never breaks the trade or the policy write.
- Config disabled → the respective listener returns early; no events suppressed (the event still fires for any other listeners/tests).

## 5. Permissions
None new. Notifications are passive; the management gate (MANAGE_SHOPS) lives in PR-A.

## 6. Build order
Single PR (PR-C), after PR-A merges. Tasks: event type → fire it in the three management methods + `policyToward` helper → announce listener → entry listener → config + lang keys → quit cleanup → gate.

## 7. Testing (TDD)
- `GuildTradePolicyService`: `setTariff`/`setEmbargo`/`clear` fire `GuildTradePolicyChangedEvent` (verify via the null-safe path — assert no throw without Bukkit; with a mocked plugin manager, assert `callEvent`); `policyToward` returns null for solo/own-guild/no-policy and the policy otherwise.
- `GuildPolicyAnnounceListener`: builds the correct message per action/kind; respects the toggle; cooldown suppresses a second rapid announce. (Unit-test the message-building + cooldown logic; broadcast itself is thin.)
- `GuildShopPolicyEntryListener`: no work when block unchanged; no work when region unchanged; warns only for a GUILD stall with a policy toward the mover; silent for solo/own-guild/no-policy; `lastRegion` updates on change; quit clears the entry. (MockBukkit `PlayerMoveEvent`, or extract the decision logic into a pure helper and unit-test that.)

## 8. Open implementation details (decided in the plan, not blockers)
- Exact lang wording/emoji — final copy in the plan.
- Cooldown keying (per-actor UUID vs per-owning-guild) — per-actor by default.
- Whether to extract the entry-decision into a pure function for easier unit testing (recommended).
