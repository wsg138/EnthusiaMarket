# EnthusiaMarket — Handoff Index

**Owner before handoff:** BadgersMC (blrusso18@gmail.com)
**Receiving agent:** `owl-alpha`
**Handoff date:** 2026-05-24
**Build status:** `./gradlew test shadowJar` green; Konsist layer rules pass; plugin compiles and enables on Paper 1.21.11.

This project follows **SPEAR** (Spec-Proven Engineering with Architectural Requirements). Read `~/.claude/CLAUDE.md` for the methodology summary before touching code.

## Read in this order

1. [tech-stack.md](tech-stack.md) — pinned versions, AI rules, CI plan
2. [requirements.md](requirements.md) — 18 EARS-validated REQs (what the system does)
3. [implementation.md](implementation.md) — layer rules, denylist, component design, data flows
4. [tasks.md](tasks.md) — 32 tagged tasks across 4 milestones; this is your queue
5. [config.md](config.md) — every config key, type, default, REQ source
6. [db-schema.md](db-schema.md) — table-by-table reference; mirrors `src/main/resources/migrations/`
7. [permissions.md](permissions.md) — full `enthusiamarket.*` permission tree
8. [dev-setup.md](dev-setup.md) — build, test, run-on-Paper, common workflows

## What's built vs. what's next

**Built (commits before 2026-05-24):**
- Domain: `Stall`, `Auction` + `Bid` (with anti-snipe), `ShopSign`, `RentTerms`, value objects + ports — fully unit-tested
- Infrastructure: Hikari + V001 schema, `WorldGuardRegionProvider`, `VaultEconomyProvider`, `LumaGuildsGuildProvider` (**stub only**)
- Application: `ImportStallsService` (idempotent WG → stall import)
- Plugin entry: Koin DI, ACF command manager, `/em import` + `/em list`
- Architecture test: Konsist layer rules pass

**Next milestones (in `tasks.md`):**
- M0 baseline: INFRA-01 CI workflow, INFRA-03 config extension, INFRA-04 permissions block, DOC-01 ref snapshots
- M1 shop signs: persist + listener + atomic trade
- M2 rent collection: scheduler + default + eviction + Vault-absent degradation
- M3 auctions: persist + start/bid/settle + anti-snipe verified
- M4 guild ownership + Bedrock UI

## Ground rules for `owl-alpha`

1. **No code before the test.** TDD tasks need `/spear:prove` first.
2. **No imports of Bukkit / Paper / WG / Vault / Koin inside `domain/`.** Konsist will block the merge.
3. **One REQ per task, one tag per task, Evidence block filled before claiming done.**
4. **Money is integer minor units everywhere.** Vault rounding lives in `VaultEconomyProvider`.
5. **Times: `java.time.Instant` in domain, epoch millis at the persistence boundary.**
6. **Main thread for all Bukkit world / inventory / entity mutations (REQ-043).**
7. **Add to `requirements.md` via `/spear:spec`**, never hand-write — the EARS validator gates it.
8. **When a config key is missing**, document in `docs/config.md` in the same commit that reads it.

## Open questions / unowned decisions

- Auction fee destination: system (sink) vs. configurable account — currently spec says system.
- Default rank id for `lumaguilds.manage-rank`: `officer` is a placeholder; verify against actual LumaGuilds rank schema before M4.
- Sign item serialization format (NBT base64 vs. item-key registry) — pick during TDD-11.
- Eviction return-to-vacant policy: do shop signs inside an evicted stall survive (frozen) or get auto-removed? Currently unspecified; add REQ before TDD-25.
