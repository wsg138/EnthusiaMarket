# Dev Setup — EnthusiaMarket

**Date:** 2026-05-24
**Owner:** BadgersMC

How to build, test, and run EnthusiaMarket locally. Read alongside `tech-stack.md` (versions) and `implementation.md` (layout).

## Prerequisites

| Tool | Min | How to verify |
|---|---|---|
| JDK | 21 | `java -version` |
| Git | 2.40+ | `git --version` |
| Paper server | 1.21.x | `paper-1.21.11.jar` for manual test |
| LuckPerms (optional) | latest | required if testing permission gating |
| Vault + EssentialsX (or any Vault econ) | 1.7 / latest | required to enable economy paths |
| LumaGuilds | local build | required for guild stall path (REQ-010) |
| Floodgate + Geyser (optional) | latest | required for Bedrock form path (REQ-011) |

Gradle wrapper bundles its own runtime — do not install Gradle globally.

## Build

```
./gradlew shadowJar
```

Produces `build/libs/EnthusiaMarket-0.1.0.jar` (shaded; Nexus + ClassGraph relocated under `net.badgersmc.em.libs.nexus.*`).

`./gradlew build` runs tests then shadowJar.

## Prerequisites

Before building, publish Nexus to mavenLocal:

```bash
cd /opt/data/nexus
./gradlew :nexus-core:publishToMavenLocal :nexus-paper:publishToMavenLocal --no-daemon
```

## Tests

```
./gradlew test                                                    # all unit + MockBukkit
./gradlew test --tests "net.badgersmc.em.architecture.*"          # Konsist layer rules only
./gradlew test --tests "net.badgersmc.em.domain.*"                # domain only (fastest)
./gradlew test --tests "*.SignRepositorySqlTest"                  # one repo
```

Konsist test enforces layer rules (REQ-101) and runs on every `gradle test`. If it fails, see `docs/implementation.md` §2 for the rule and `Forbidden Domain Annotations` denylist.

## Run on a local Paper server

1. Build: `./gradlew shadowJar`
2. Drop jar into `<paper>/plugins/`
3. Drop hard dependencies: `LumaGuilds.jar`, `WorldGuard.jar`, `Vault.jar` + an economy plugin
4. Start server: `java -Xmx4G -jar paper-1.21.11.jar nogui`
5. First boot writes `plugins/EnthusiaMarket/config.yml` (defaults from `src/main/resources/config.yml`)
6. Stop server, edit config per `docs/config.md`, restart

Define a few stalls in WorldGuard first:

```
/region define stall_001 -p
/region define stall_002 -p
/em import     # idempotent — registers every WG region matching market.region-prefix
/em list
```

## Bedrock testing (optional)

Drop `Geyser-Spigot.jar` + `Floodgate-Spigot.jar` in `plugins/`. Confirm `bedrock.force-forms: false` (default). Join with a Bedrock client — stall menus render as Cumulus forms (REQ-011). For Java-only dev, set `bedrock.force-forms: true` to exercise the Cumulus path from a Java client.

## Database

Default is SQLite at `plugins/EnthusiaMarket/enthusiamarket.db`. Schema lives in `src/main/resources/migrations/V###__*.sql` and applies at enable (REQ-042). See `docs/db-schema.md` for shape + relationships.

To switch to MariaDB for staging:

```yaml
database:
  type: mariadb
  mariadb:
    host: mariadb.host
    port: 3306
    database: enthusiamarket
    username: em
    password: <secret>
```

## Common workflows

| You want to... | Do this |
|---|---|
| Add a new REQ | `/spear:spec` — drafts EARS entry, validates, appends to `requirements.md`, assigns next free ID |
| Start a TDD task | mark `[~]` in `tasks.md`, `/spear:prove` (failing test), `/spear:engine` (implement), `/spear:arch`, `/spear:refine` |
| Verify layer rules | `./gradlew test --tests "*.LayerRulesTest"` |
| Find a port impl | `Grep` for `: PortName` under `src/main/kotlin/net/badgersmc/em/infrastructure/` |
| Run the EARS validator manually | `node ~/.claude/plugins/cache/BadgersMC-spear-plugin/spear/0.1.0/hooks/lib/ears.mjs docs/requirements.md` |

## Conventions

- **DI framework:** Nexus (`@Service`, `@Repository`, `@Component`) — constructor injection with classpath scanning. No manual module wiring.
- **Config:** Nexus `@ConfigFile` annotated classes in `src/main/kotlin/net/badgersmc/em/config/`. Generated as `enthusiamarket.yaml`.
- **Commands:** Nexus Paper `@Command` / `@Subcommand` with `@Context`, `@Permission`, `@PlayerOnly` annotations.
- Package layout: `domain/`, `application/`, `infrastructure/`, `config/`. Bukkit / framework imports live only under `infrastructure/`, `config/`, and `EnthusiaMarket.kt`.
- Money: integer minor units everywhere; `EconomyProvider` boundary handles the Vault `double` rounding.
- Times: `java.time.Instant` in domain, epoch millis on the wire / in DB.
- Tests for domain logic use plain JUnit + MockK; Bukkit-touching tests use MockBukkit.
- Avoid `Bukkit.getScheduler().runTaskAsynchronously` for state-mutating work — main thread only (REQ-043).
