# Tech Stack — EnthusiaMarket

**Date:** 2026-05-24
**Status:** Bootstrap (emitted by `/spear:init` — fill placeholders, commit, then revise as the project evolves)
**Owner:** BadgersMC

## 1. What this project is

EnthusiaMarket is a Paper plugin (shaded JAR, target `plugins/`) that turns WorldGuard regions into rentable/ownable player- and guild-operated market stalls with sign shops and timed item auctions. Consumed by survival-economy players on a Paper 1.21.x server, including Bedrock clients via Floodgate. Deployment target: BadgersMC production network.

## 2. Runtimes & languages

| Layer | Language / Tool | Min version | Reason |
|---|---|---|---|---|
| Plugin | Kotlin | 2.0.0 | Concise domain model, null safety, coroutine-ready |
| Build tool | Gradle (Kotlin DSL) + Shadow | 8.x / 8.3.6 | Standard for Paper plugins; Shadow relocates Nexus + ClassGraph |
| Test framework | JUnit 5 + MockK + MockBukkit | 5.8.1 / 1.13.11 / 4.107.0 | Idiomatic Kotlin tests + Bukkit-API simulation |
| DI + Config + Commands | **Nexus** (nexus-core + nexus-paper) | 1.6.0 | Internal BadgersMC framework: classpath-scanning DI, @ConfigFile YAML config, Paper Brigadier @Command/@Subcommand |
| JVM | JDK 21 | — | Paper 1.21.x minimum |
| CI runner | GitHub Actions (planned) | — | Single workflow per project standard |

Detected by `/spear:init` from `build.gradle.kts` (Gradle Kotlin DSL).

## 3. Runtime dependencies

Top-level direct dependencies with pinned versions. Transitive pins live in the lockfile (none yet — Gradle resolution cache).

| Package | Version | Why |
|---|---|---|
| io.papermc.paper:paper-api | 1.21.11-R0.1-SNAPSHOT | Server API (compileOnly) |
| com.github.MilkBowl:VaultAPI | 1.7 | Economy abstraction (compileOnly) |
| com.sk89q.worldguard:worldguard-bukkit | 7.0.9 | Region source for stalls (compileOnly) |
| org.geysermc.floodgate:api | 2.2.5-SNAPSHOT | Detect Bedrock players (compileOnly) |
| org.geysermc.cumulus:cumulus | 2.0.0-SNAPSHOT | Bedrock UI forms (compileOnly) |
| net.badgersmc:nexus-core | 1.6.0 | DI container, @ConfigFile YAML config, coroutines (shaded, relocated) |
| net.badgersmc:nexus-paper | 1.6.0 | Paper Brigadier @Command/@Subcommand system, BukkitDispatcher (shaded, relocated) |
| com.zaxxer:HikariCP | 5.1.0 | Connection pool |
| org.xerial:sqlite-jdbc | 3.45.1.0 | Default embedded DB |
| org.mariadb.jdbc:mariadb-java-client | 3.3.2 | Production DB option |
| org.slf4j:slf4j-nop | 2.0.13 | Silence Nexus SLF4J binding |

## 4. Pinned external schemas

External APIs and on-the-wire schemas this project depends on. Snapshot each in `docs/refs/` so a breaking upstream change is caught in review, not in production.

| Schema | Source of truth | Snapshot location |
|---|---|---|
| WorldGuard region API | maven.enginehub.org WG 7.0.9 javadocs | `docs/refs/worldguard-7.0.9.md` (TODO) |
| Vault Economy interface | Vault 1.7 javadocs | `docs/refs/vault-economy-1.7.md` (TODO) |
| Cumulus form schema | Floodgate/Cumulus 2.0 docs | `docs/refs/cumulus-2.0.md` (TODO) |
| LumaGuilds public API | LumaGuilds plugin repo | `docs/refs/lumaguilds-api.md` (TODO) |

## 5. AI / agent rules

1. **Verify, don't guess.** Before writing code, confirm library APIs via context7 MCP, library source on disk, official docs via WebFetch, or `mgrep`/`Read`/`Glob` in that order. Record consulted sources in the task's `Evidence:` block.
2. **Use context7 MCP** for up-to-date library docs; prefer it over re-reading large source trees.
3. **Use mgrep** for code search when the skill is available.
4. **Use semgrep** for pattern / security scans.
5. **Briefing contract.** Any subagent dispatch carries: file paths, pre-verified signatures, the failing test (for TDD tasks), acceptance criteria, forbidden actions, and the task's Evidence block.
6. **Task sizing.** If a worker briefing exceeds ~1500 tokens, `/spear:spec` decomposes the task further before dispatch.

## 6. Versioning

Semantic versioning. Project starts at `0.1.0`; bump major on breaking public-API change.

## 7. CI

GitHub Actions (planned) — single workflow at `.github/workflows/build.yml`.

1. Build / compile (`./gradlew shadowJar`)
2. Unit tests (`./gradlew test`)
3. Architecture tests (Konsist on JVM)
4. Lint / formatter (ktlint, planned)
5. plugin.yml validation

## 8. Out of stack

Explicit non-goals for the toolchain — frameworks, languages, or infrastructure this project will NOT adopt without a spec change.

- Spring / Jakarta / Micronaut frameworks in `domain/`
- ORM (JPA/Hibernate/Exposed) — IDB/raw SQL via Hikari only
- Non-Paper server forks (Spigot/Bukkit only mode) — Paper API required
- Reflection-heavy serializers (Jackson/Gson) inside domain
