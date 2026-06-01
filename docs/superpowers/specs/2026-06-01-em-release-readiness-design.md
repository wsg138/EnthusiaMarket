# EM Release Readiness — Design

**Date:** 2026-06-01
**Status:** Approved (brainstorming complete)
**Owner:** BadgersMC
**Target:** Next-week production release on BadgersMC network

## 1. Problem

Audit of EnthusiaMarket (EM) before release found four shipped-but-unwired features,
a permission-declaration drift bug that locks players out of core commands, and — most
critically — a WorldGuard region-provisioning gap that means **27 of 71 market stalls
are unbuildable** and the entity-limit feature governs entities players cannot fully use.

EM is replacing ARM (Advanced Region Market). ARM is what configured the working stalls'
region flags. If EM does not take over flag provisioning, newly-carved stalls and the 27
already-dead stalls stay unusable.

## 2. Evidence from production region data

Source: `D:\regions.yml` (WorldGuard regions, production world) and the WG `config.yml`.

### 2.1 The nesting

- `spawn` — priority 10, safezone (build implicitly denied, `use/interact/chest-access` denied)
- `market` — priority 10, safezone inside spawn. **No members, no owners, no `build` flag**
  → implicitly denies build to everyone. Explicitly denies `use`, `interact`,
  `chest-access`, `item-frame-rotation`, `entity-item-frame-destroy`.
- Stalls — cuboid regions inside `market`.

### 2.2 Two camps of stalls

| Camp | Regions | Priority | Flags | Buildable by members? |
|---|---|---|---|---|
| Legacy ARM | `stall1`–`stall44` (44) | **20** (> market) | `build: allow` + `build-group: MEMBERS`, `use-group: ALL`, chest/place/break/ride all MEMBERS | ✅ yes |
| Unconfigured | `stall45`–`stall71` (27) | **0** (< market) | `{}` empty | ❌ no — market (pri 10) wins |

`RegionMemberSync` (EM today) edits only owner/member **lists** — never priority or flags.
Adding a member to a priority-0 stall does nothing; market still outranks it.

### 2.3 WG config confirmations (`config.yml`)

- `build-permission-nodes.enable: false` → build governed by **flags + membership only**,
  not permission nodes. Flag provisioning is the correct mechanism.
- `high-frequency-flags: true` → `block-place`/`block-break` flags are active and required.
- `use-creature-spawn-event: true` → entity-limit `CreatureSpawnEvent` listener is viable.
- `op-permissions: true` + `disable-bypass-by-default: false` → **ops bypass all region
  protection.** Testing F as an op masks the bug entirely. **All F and entity-limit tests
  must run in a non-op context** (or `/rg bypass` disabled).

### 2.4 regionPrefix mismatch

Production regions are named `stall1`..`stall71` (no underscore). EM config default is
`regionPrefix: "stall_"` → matches **zero** regions. Must be `"stall"`. `trade_stalls`
does not false-match (starts with `trade`).

## 3. Scope — six workstreams

Build order is dependency-correct with the release-blocking provisioning fix first.

### Workstream 0 — Config fixes (no code)

- `market.regionPrefix`: `"stall_"` → `"stall"`.
- Add `market.stallPriority: Int = 20` to `EnthusiaMarketConfig.Market`.

Release-blocking: every other workstream keys off matched regions.

### Workstream F — Region build provisioning (TOP PRIORITY)

New SPEAR tasks: **TDD-280** (provision domain/port + adapter), **TDD-281** (import wiring).

- New domain port `RegionProvisioner`:
  ```
  fun provision(world: String, regionId: String, priority: Int)
  ```
  Idempotent: sets region priority and the flag set below. Safe to re-run.
- New WG infra adapter implementing `RegionProvisioner` via WorldGuard API
  (`ProtectedRegion.setPriority`, `setFlag(Flags.BUILD, ALLOW)`,
  `setFlag(Flags.BUILD.regionGroupFlag, RegionGroup.MEMBERS)`, etc.), then
  `RegionManager.save()`.
- Kept **separate** from `RegionMemberSync` (which stays owner/member-list only).
- Flag set written per region (core + decoration):
  - Core: `build: allow` (+ group MEMBERS), `use: allow` (+ group ALL),
    `chest-access: allow` (+ group MEMBERS), `block-place: allow` (+ group MEMBERS),
    `block-break: allow` (+ group MEMBERS), `ride: allow` (+ group MEMBERS).
  - Decoration: `item-frame-rotation: allow`, `interact: allow` (member-grouped).
- Priority written = `config.market.stallPriority` (default 20), always above `market` (10).
- Wired into `/em import`: every matched region is provisioned on import. Brings all 71
  stalls (incl. the 27 dead ones) to parity and makes decoration entities functional.

**Rationale for decoration flags:** `entitylimits.yml` caps `item_frame`/`armor_stand`;
market denies `interact` + `item-frame-rotation` and ARM stalls never re-allowed them, so
today players can place frames but cannot rotate them or insert items, and armor-stand
equip/pose is half-broken. Capping entities players cannot fully use is incoherent.

**Test note:** all provisioning tests run non-op (op bypass would mask failure).

### Workstream E2 — Permissions DSL

Replace the hand-maintained `permissions:` block in `paper-plugin.yml` with the
`nexus-permissions-gradle` typed Kotlin DSL, generated at build time.

- Apply `nexus-permissions-gradle` (v2.2.1) via `buildscript { classpath(...) }` +
  `apply(plugin = "net.badgersmc.nexus.permissions")`, configure the tree extension.
- Declare the full permission tree, including nodes currently **used in code but undeclared**
  in `paper-plugin.yml` (the drift bug): `enthusiamarket.stall.buy`,
  `enthusiamarket.stall.offer`, `enthusiamarket.stall.sellback`,
  `enthusiamarket.stall.members`. Undeclared nodes default OP-only → players silently
  locked out of offer/sellback/buy/members.
- Include new nodes for F/B/C/D commands as they are built (`stall.setkind`,
  `stall.entitylimit`, `stall.recount`, `stall.info`, `stall.outline`).
- Strip the `permissions:` block from `paper-plugin.yml`; the merger regenerates it into
  the staged resource before `shadowJar`. Drift becomes structurally impossible.

**Dropped — E1 (`nexus-papi`):** verified direction mismatch. `nexus-papi` registers EM
*as a PAPI expansion* (provide-side: other plugins read `%enthusiamarket_x%`). EM's
`OwnerNameResolver.expandPapiIfAvailable` *consumes* third-party placeholders
(`PlaceholderAPI.setPlaceholders`). `nexus-papi` has no consume-side helper. The existing
reflection stays untouched.

### Workstream B — Entity limits (TDD-220 + TDD-221)

- **Domain:** `RegionKind` (value type wrapping kind name); `EntityLimitGroup`
  (`Map<EntityType, Int>` + `_total`, `-1` = unlimited; merges per-stall extras).
- **Stall fields:** `kind: String = "default"`, `extraEntities: Map<String, Int>`,
  `extraTotal: Int`.
- **Migration V013:** `stalls` gains `kind` (default `'default'`), `extra_entities` (JSON
  text), `extra_total` (int default 0). Backfill existing rows to `kind='default'`. Repo
  read/write updated. Import sets kind (`default` unless overridden).
- **Loader:** `EntityLimitConfig` parses `entitylimits.yml` → `Map<String, EntityLimitGroup>`.
- **Counting — hybrid:** `StallEntityCounter` (@Component) holds in-memory
  `Map<stallId, Map<EntityType, Int>>`. Increments on allowed spawn; decrements on
  `EntityDeathEvent`/remove. Fast path = cache; at the cap boundary, do one authoritative
  `world.getNearbyEntities(bounds)` rescan before cancelling, to avoid false cancels from
  cache drift.
- **Listener:** `EntityLimitListener` on `CreatureSpawnEvent` filtered to
  player-attributable `SpawnReason`s (BREEDING, SPAWNER_EGG, BUILD_IRONGOLEM,
  BUILD_SNOWMAN, BUILD_WITHER, DISPENSE_EGG, EGG, etc. — natural spawns ignored), plus
  always `EntityPlaceEvent` and `HangingPlaceEvent`. Resolves enclosing stall via WG →
  loads Stall → checks merged cap → cancels when over.
- **Commands:** `/em stall setkind <stall> <kind>`, `/em stall entitylimit set <stall>
  <type> <n>` (REQ-222 per-stall override), `/em stall recount <stall>` (force cache rescan).
- **Depends on F:** players must be able to place the capped entities first.

### Workstream C — Region info card (TDD-230)

- Extend `RegionProvider` with `bounds(world, id): RegionBounds?` (max − min per axis).
- `StallInfoService` (application) returns structured `StallInfo` with 9 fields: stall id,
  region kind, owner, member count, current rent, time until next rent collection,
  dimensions in blocks, state, available-to-claim.
- Thin infra renderer formats `StallInfo` to MiniMessage via new `stall.info.*` lang keys.
- Wired to **both** `/em stall info <stall>` and a new INFO branch in
  `PurchaseSignClickListener` (non-owner right-click on an INFO sign → same card).

### Workstream D — Particle outline (TDD-240)

- `ParticleBorderService` (@Component): tracks `(player, stall, expiresAt)` triples; Bukkit
  repeat task at 4-tick interval traces the WG region bounding box with `Particle.END_ROD`,
  visible only to the requesting player.
- **Global budget:** single spacing value derived from total perimeter across all active
  outlines vs. `config.particles.maxPerTick` (default 200). Degrade by widening spacing
  uniformly, never by dropping outlines. Test asserts total particle count per tick ≤
  `maxPerTick`.
- Command `/em stall outline <stall> [seconds]`, default 10s.

## 4. Architecture (hexagonal / SPEAR layers)

- **Domain (new):** `RegionKind`, `EntityLimitGroup`, `RegionBounds`, `StallInfo`,
  `RegionProvisioner` port; `Stall` gains `kind`/`extraEntities`/`extraTotal`.
- **Application (new):** `StallInfoService`, `StallEntityCounter`, `ParticleBorderService`,
  `EntityLimitConfig` loader; `StallInfoService` consumes `RegionProvider.bounds`.
- **Infrastructure (new):** WG `RegionProvisioner` adapter, `EntityLimitListener`,
  info-card MiniMessage renderer, new `AdminCommands` subcommands; `RegionProvider` WG
  adapter gains `bounds`.
- **Build (new):** `nexus-permissions-gradle` plugin generates the permission tree.

Layer rule unchanged: domain imports nothing from application/infra; application imports
only domain; infra imports all + frameworks. Konsist `LayerRulesTest` continues to enforce.

## 5. Decisions locked

| Decision | Choice |
|---|---|
| RegionKind resolution | Stored column on Stall (migration V013) |
| Entity counting | Hybrid (cache + boundary rescan) |
| Spawn enforcement scope | Player-attributable spawn reasons + place events |
| Particle budget split | Single global spacing (total perimeter ÷ maxPerTick) |
| Permissions | E2 only (nexus-permissions-gradle); E1 nexus-papi dropped |
| Region provisioning | Provision-on-import, core + decoration flag set |
| Provisioning priority | `config.market.stallPriority` (default 20) |

## 6. Test strategy

- Domain logic (`EntityLimitGroup` merge, `StallInfo` field population, `RegionBounds`
  dimensions): pure JUnit + MockK, red-first per SPEAR.
- Listeners (`EntityLimitListener` cancel at/under cap, INFO sign routing): MockBukkit.
- Provisioning (F): assert priority + each flag/group written; **non-op context only**.
- Particle budget: assert total particle count per tick ≤ `maxPerTick`.
- Permissions: verify build-time generation by inspecting staged
  `build/resources/main/paper-plugin.yml`.
- Full suite green: `./gradlew detekt test shadowJar jacocoTestReport`.

## 7. Build order (dependency-correct)

1. **Workstream 0** — config fixes (regionPrefix, stallPriority).
2. **F** — region build provisioning (release-blocking; unblocks everything downstream).
3. **E2** — permissions DSL (fixes drift; small, independent).
4. **B** — entity limits (largest; depends on F for placement).
5. **C** — region info card (needs `RegionProvider.bounds`; independent of B).
6. **D** — particle outline (fully independent; last).

Each workstream follows the SPEAR cycle (Spec → Prove → Engine → Arch → Refine) and updates
`docs/tasks.md` as tasks complete. New tasks TDD-280/281 added for F.

## 8. Out of scope (deferred post-release)

- Java inventory GUI (`UiDispatcher` TODO, TDD-43) and Bedrock create-shop forms (TDD-60) —
  existing placeholders, not release-blocking.
- E1 `nexus-papi` migration — direction mismatch; existing PAPI consume-side reflection works.
- Provision-on-claim dynamics (option B for F) — import-time provisioning is sufficient.
