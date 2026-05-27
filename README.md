# EnthusiaMarket

[![build](https://github.com/BadgersMC/EnthusiaMarket/actions/workflows/build.yml/badge.svg)](https://github.com/BadgersMC/EnthusiaMarket/actions/workflows/build.yml)

Paper 1.21.x plugin that turns WorldGuard regions into rentable / ownable player- and guild-operated market stalls, with sign-shops and timed item auctions. Bedrock-aware via Floodgate + Cumulus forms.

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
| Language | Kotlin 2.0.0 on JDK 21 |
| Server API | Paper 1.21.11 (compileOnly) |
| Build | Gradle 8.10.2 + Shadow 8.3.6 |
| DI / config / commands / i18n / persistence / scheduler / vault | [Nexus](https://github.com/BadgersMC/Nexus) 1.11.0 (shaded, relocated under `net.badgersmc.em.libs.nexus.*`) |
| Persistence | HikariCP + SQLite (default) or MariaDB; migrations in `src/main/resources/migrations/` |
| Integrations | WorldGuard 7.0.9, VaultAPI 1.7, Floodgate / Cumulus 2.x, LumaGuilds |
| Tests | JUnit 5, MockK 1.13.11, MockBukkit 4.107.0, Konsist 0.17.3 |

See [`docs/tech-stack.md`](docs/tech-stack.md) for full pin list + rationale.

## Build

```bash
./gradlew shadowJar
```

Produces `build/libs/EnthusiaMarket-0.1.0.jar`.

### Local prerequisites

EnthusiaMarket depends on two artifacts that aren't on Maven Central and need to be wired before a local build:

1. **Nexus 1.11.0** — published to [BadgersMC GitHub Packages](https://github.com/orgs/BadgersMC/packages?repo_name=Nexus). Add the credentials once to `~/.gradle/gradle.properties`:

   ```properties
   gpr.user=<your-github-username>
   gpr.token=<personal-access-token-with-read:packages>
   ```

   Gradle will resolve every `net.badgersmc:nexus-*` artifact automatically. If you're hacking on Nexus locally and want to pick up your in-progress changes, publish with `-PuseMavenLocal=true` instead:

   ```bash
   git clone https://github.com/BadgersMC/Nexus.git
   cd nexus
   ./gradlew -PuseMavenLocal=true publishToMavenLocal
   ```

   Then run EM's Gradle with the same flag: `./gradlew -PuseMavenLocal=true build`.

2. **LumaGuilds jar** — point the build at it via either:
   - `-Plumaguilds.jar=/abs/path/to/LumaGuilds-2.1.0.jar`, or
   - `LUMAGUILDS_JAR=/abs/path/to/LumaGuilds-2.1.0.jar` env var.

   Default fallback (the BadgersMC dev VPS path) is `/opt/data/LumaGuilds/build/libs/LumaGuilds-2.1.0.jar`.

   LumaGuilds itself needs `libs/RoseChat-RC-2.jar` to compile — build it from [BadgersMC/Enthusia-RoseChat](https://github.com/BadgersMC/Enthusia-RoseChat) (`./gradlew shadowJar`) and drop the resulting jar into `LumaGuilds/libs/`.

CI uses the auto-provided `GITHUB_TOKEN` for the Nexus repo, so no extra secrets are needed on the EM workflow — see [`.github/workflows/build.yml`](.github/workflows/build.yml) for the full chain.

## Test

```bash
./gradlew test                                                    # full suite (~255 tests)
./gradlew test --tests "net.badgersmc.em.architecture.*"          # Konsist layer rules only
./gradlew test --tests "net.badgersmc.em.domain.*"                # domain (fastest)
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
