# WorldGuard 7.0.9 — API Snapshot

**Source:** enginehub/worldguarddocs (context7) + enginehub/worldguard 7.0.9
**Pinned version:** `com.sk89q.worldguard:worldguard-bukkit:7.0.9`
**Snapshot date:** 2026-05-24

## Key Classes

| Class | Package | Role |
|---|---|---|
| `WorldGuard` | `com.sk89q.worldguard` | Static entry point |
| `RegionContainer` | `com.sk89q.worldguard.protection` | Access region managers |
| `RegionManager` | `com.sk89q.worldguard.protection.regions` | Region CRUD per world |
| `RegionQuery` | `com.sk89q.worldguard.protection.regions` | Cached spatial queries |
| `ApplicableRegionSet` | `com.sk89q.worldguard.protection` | Query result set |
| `ProtectedRegion` | `com.sk89q.worldguard.protection.regions` | Region data |
| `ProtectedCuboidRegion` | `com.sk89q.worldguard.protection.regions` | Cuboid region type |
| `BlockVector3` | `com.sk89q.worldedit.math` | 3D integer vector |
| `BukkitAdapter` | `com.sk89q.worldedit.bukkit` | Bukkit→WorldEdit adapter |

## Critical Signatures

### Accessing regions
```java
RegionContainer container = WorldGuard.getInstance().getPlatform().getRegionContainer();
RegionManager regions = container.get(BukkitAdapter.adapt(world));
RegionQuery query = container.createQuery();
```

### Listing all regions for a world
```java
Map<String, ProtectedRegion> all = regions.getRegions(); // id → region
```

### Test if a named region exists
```java
ProtectedRegion region = regions.getRegion("stall_001"); // null if absent
```

### Point query (is a location inside a region?)
```java
Location Bukkit API → com.sk89q.worldedit.util.Location via BukkitAdapter.adapt(loc)
ApplicableRegionSet set = query.getApplicableRegions(loc);
for (ProtectedRegion r : set) { /* ... */ }
```

### Overlap query (enumerate regions overlapping an area)
```java
ProtectedRegion dummy = new ProtectedCuboidRegion("_snapshot_",
    BlockVector3.at(minX, minY, minZ),
    BlockVector3.at(maxX, maxY, maxZ));
ApplicableRegionSet overlapSet = regions.getApplicableRegions(dummy);
```

## Enums Used by EnthusiaMarket

| Enum | Values |
|---|---|
| `RegionQuery.QueryOption` | `COMPUTE_REGION_CHILDREN`, `ALLOW_VIRTUAL` |

## Breaking-Change Watchpoints

1. `RegionContainer.get(BukkitWorld)` — return type may be null if world not loaded.
2. `ProtectedRegion.getId()` — region IDs are case-sensitive.
3. `WorldGuard.getInstance().getPlatform().getRegionContainer()` — WG must be loaded; call after `onEnable` or in a `PluginEnableEvent` listener.

## Evidence

- context7:/enginehub/worldguarddocs — spatial queries, region access, adapter usage
- com.sk89q.worldguard:worldguard-bukkit:7.0.9 (pinned in build.gradle.kts:26)
