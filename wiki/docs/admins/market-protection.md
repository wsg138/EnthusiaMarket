---
title: Market Protection
audience: admin
topic: market-protection
summary: Runtime protections enforced inside stall regions — potion-effect blocking (anti-stacking exploit fix) and entity caps.
keywords: [potion, splash, lingering, effect, exploit, protection, entity-limit]
related: [maintenance, config, troubleshooting]
updated: 2026-08-02
---

# Market Protection

Runtime invariants enforced for **every** stall region, independent of WorldGuard flag state.

## Potion effects are blocked in market regions (REQ-305)

Since MC 1.21 applies splash/lingering potion effects **additively**, a player in their stall could stack effect durations without bound — repeated splashes or a lingering cloud re-applying each tick produced absurd effects (observed: Resistance IV with a ~52-day duration). This is a critical exploit.

The plugin now cancels every potion-effect delivery targeting an entity inside a stall-prefixed region:

| Vector | Event cancelled |
|---|---|
| Thrown splash potion breaking in a stall | `PotionSplashEvent` |
| Thrown lingering potion breaking in a stall | `LingeringPotionSplashEvent` |
| Lingering cloud applying effects to an entity in a stall (incl. clouds drifting in from outside) | `AreaEffectCloudApplyEvent` |
| Tipped arrows / splash / cloud applying an effect to an entity in a stall | `EntityPotionEffectEvent` (causes `POTION_SPLASH`, `AREA_EFFECT_CLOUD`, `ARROW`) |

Notes:

- The WorldGuard `POTION_SPLASH: DENY` flag exists in the region provisioner, but it is stamped **only at provision/resync time** — existing regions never received it, and WG's flag covers only the thrown-splash event. The runtime listener above is the authoritative enforcement for all regions and closes the lingering-cloud and tipped-arrow vectors too.
- **Not blocked:** drinking potions yourself, beacon effects, milk, effect removal — only *splash/cloud/arrow-delivered* applications to entities inside stall regions are cancelled.
- Effects already stacked **before** this fix (e.g. the Resistance IV in the report) are not removed by the plugin — players can clear them with milk or `/effect clear`.

## Entity limits in stalls

Per-stall entity caps (configured in `entitylimits.yml`) limit player-attributable creature spawns and entity/hanging placements. See the config file comments for group-based limits.
