# EnthusiaMarket

[![build](https://github.com/BadgersMC/EnthusiaMarket/actions/workflows/build.yml/badge.svg)](https://github.com/BadgersMC/EnthusiaMarket/actions/workflows/build.yml) [![Codacy Badge](https://app.codacy.com/project/badge/Grade/f9477623e26341ad9ea58c04fd174815)](https://app.codacy.com/gh/BadgersMC/EnthusiaMarket/dashboard?utm_source=gh&utm_medium=referral&utm_content=&utm_campaign=Badge_grade) [![Codacy Badge](https://app.codacy.com/project/badge/Coverage/f9477623e26341ad9ea58c04fd174815)](https://app.codacy.com/gh/BadgersMC/EnthusiaMarket/dashboard?utm_source=gh&utm_medium=referral&utm_content=&utm_campaign=Badge_coverage)

Paper 26.2 plugin that turns WorldGuard regions into rentable / ownable player- and guild-operated market stalls, with sign-shops and timed item auctions. Bedrock-aware via Floodgate + Cumulus forms.

Built for the BadgersMC production network. Java + Bedrock clients both supported.

## Highlights

- **Stalls from WorldGuard regions** — `/em import` is idempotent; existing regions register as stalls automatically.
- **Sign shops** — atomic buy/sell against a linked container, with explosion- and break-protection for owners.
- **Rent + eviction** — scheduler-driven, configurable terms, graceful degradation when Vault is missing.
- **Timed auctions** — start / bid / settle with anti-snipe extension.
- **Guild ownership** — via [LumaGuilds](https://github.com/BadgersMC/LumaGuilds) (per-rank permissions, not rank-name strings).
- **Bedrock UI** — Cumulus forms when the player is Bedrock (or `bedrock.force-forms: true` for testing).
- **Hexagonal layout** — strict `domain` / `application` / `infrastructure` separation, enforced by Konsist on every `gradle test`.

## Tech stack

| Layer | Choice |
|---|---|
| Language | Kotlin 2.3.20 on JDK 25 |
| Server API | Paper 26.2 (compileOnly) |
| Build | Gradle 9.1.0 + Shadow 8.3.11 |
| DI / config / commands / i18n / persistence / scheduler / vault | [Nexus](https://github.com/BadgersMC/Nexus) v2.3.0 (shaded, relocated under `net.badgersmc.em.libs.nexus.*`) |
| Persistence | HikariCP + SQLite (default) or MariaDB; migrations in `src/main/resources/migrations/` |
| Integrations | WorldGuard 7.0.9, VaultAPI 1.7, Floodgate / Cumulus 2.x, LumaGuilds |
| Tests | JUnit 5, MockK 1.13.11, MockBukkit 4.116.1 (26.2), Konsist 0.17.3 |

See [`docs/tech-stack.md`](docs/tech-stack.md) for full pin list + rationale.

## Build

```bash
./gradlew shadowJar -PuseMavenLocal=true
```

Produces `build/libs/EnthusiaMarket-0.1.0.jar`.

### Local prerequisites

EnthusiaMarket depends on two artifacts that aren't on Maven Central and need to be wired before a local build:

1. **Nexus v2.3.0** — the Paper 26.2 support is merged at commit `057836befb9e35aa252cf90104030ec86f28b33f`, but v2.3.0 is not tagged/published yet. Build that exact commit to Maven Local before building Market:

   ```bash
   git clone https://github.com/BadgersMC/Nexus.git
   cd Nexus
   git checkout 057836befb9e35aa252cf90104030ec86f28b33f
   ./gradlew publishToMavenLocal
   ```

   Then build Market with `-PuseMavenLocal=true`. Composite builds can set `USE_MAVEN_LOCAL_NEXUS=true` instead; environment variables propagate into Gradle included builds whereas root `-P` properties do not. CI and release use the same pinned bootstrap so they do not depend on an unpublished JitPack coordinate.

2. **LumaGuilds jar** — point the build at it via either:
   - `-Plumaguilds.jar=/abs/path/to/LumaGuilds-3.0.0.jar`, or
   - `LUMAGUILDS_JAR=/abs/path/to/LumaGuilds-3.0.0.jar` env var.

   Alternatively place a current `LumaGuilds-*.jar` in Market's local `libs/` directory. CI pins the published LumaGuilds 3.0.0 release and does not rebuild LumaGuilds or RoseChat from source.

CI needs no extra secrets. See [`.github/workflows/build.yml`](.github/workflows/build.yml) for the exact pinned dependency bootstrap.

## Test

```bash
./gradlew test -PuseMavenLocal=true                                          # full suite
./gradlew test -PuseMavenLocal=true --tests "net.badgersmc.em.architecture.*" # Konsist layer rules
./gradlew test -PuseMavenLocal=true --tests "net.badgersmc.em.domain.*"       # domain (fastest)
```

Konsist enforces the hexagonal boundary on every run — domain code must not import Bukkit / Paper / WG / Vault / Koin / Nexus. If layer rules fail, see [`docs/implementation.md`](docs/implementation.md) §2.

## Run on a Paper server

1. `./gradlew shadowJar`
2. Drop the shaded jar into `<paper>/plugins/`
3. Drop hard deps alongside: `LumaGuilds.jar`, `WorldGuard.jar`, `Vault.jar` + any economy plugin
4. Start the server — first boot writes `plugins/EnthusiaMarket/config.yml` with defaults
5. Stop, edit config (see [`docs/config.md`](docs/config.md)), restart

Define a few stalls in WorldGuard, then import:

```
/region define stall_001 -p
/region define stall_002 -p
/em import
/em list
```

For Bedrock testing add `Geyser-Spigot.jar` + `Floodgate-Spigot.jar`; stall menus render as Cumulus forms automatically.

## Docs

| Doc | What it covers |
|---|---|
| [`docs/requirements.md`](docs/requirements.md) | 18 EARS-validated requirements (REQ IDs are the source of truth) |
| [`docs/implementation.md`](docs/implementation.md) | Architecture blueprint, layer rules, denylist, data flows |
| [`docs/tasks.md`](docs/tasks.md) | Granular tasks across 4 milestones; current development queue |
| [`docs/tech-stack.md`](docs/tech-stack.md) | Pinned versions + AI rules |
| [`docs/config.md`](docs/config.md) | Every config key, type, default, REQ source |
| [`docs/db-schema.md`](docs/db-schema.md) | Tables, columns, relationships; mirrors `migrations/` |
| [`docs/permissions.md`](docs/permissions.md) | Full `enthusiamarket.*` permission tree |
| [`docs/dev-setup.md`](docs/dev-setup.md) | Detailed dev environment + common workflows |

## SPEAR

This project follows **SPEAR** — Spec-Proven Engineering with Architectural Requirements (TDD + EARS specs + hexagonal architecture). The flow per task:

1. **Spec** — every behavior maps to a `REQ-` id in `requirements.md` (managed via `/spear:spec`)
2. **Prove** — write a failing test first (`/spear:prove`)
3. **Engine** — minimum code to flip the test green (`/spear:engine`)
4. **Arch** — Konsist + denylist check (`/spear:arch`)
5. **Refine** — refactor, full suite green, mark `[x]` in `tasks.md` (`/spear:refine`)

If it isn't in `requirements.md`, it doesn't get built. If `requirements.md` is wrong, fix it first via `/spear:spec`.

## License

TBD — no license file in the repo yet. Treat as proprietary to BadgersMC pending a decision.
