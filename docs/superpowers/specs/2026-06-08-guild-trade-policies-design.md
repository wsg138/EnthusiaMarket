# Guild Trade Policies (Tariffs & Embargoes) — Design

**Date:** 2026-06-08
**Status:** Approved (brainstorming complete)
**Owner:** BadgersMC
**Plugin:** EnthusiaMarket (EM)

## 0. Summary

Guild-owned shops gain **economic control over other guilds**: a guild can set, per other guild, either a **tariff** (a percentage that worsens that guild's trade rate) or an **embargo** (that guild can't trade at the shop at all). This is a new layer on top of the existing guild-owned container-shop trade path.

Guiding decisions (settled in brainstorming):

| Question | Decision |
|---|---|
| Targeting | **Per-target-guild** rules (a guild keeps a small table: target guild → tariff% or embargo) |
| Surcharge destination | **Owning guild's bank** — implicit (the bank is already the trade counterparty) |
| Solo / own-guild buyers | **Always exempt** — policies apply only to members of *other* guilds |
| Direction | **Symmetric** — outsiders trade at a worse rate both ways; the guild keeps the difference. Embargo blocks both directions |
| Management | Members with the guild **MANAGE_SHOPS** permission, via a **GUI** |

## 1. Scope

In scope: guild-owned **container shops** (the only path guild shops trade through — sign-trades on guild stalls are already rejected, C-8). A per-guild policy table, a resolution service, a ~3-line hook in `ContainerTradeService`, a management GUI + command, a `listGuilds` port addition, a migration, and a disband-cleanup tie-in.

Out of scope (YAGNI): discounts / negative tariffs (a 0% tariff already = base price; allies just get no policy), relationship-tier automation (LumaGuilds ally/neutral/enemy), policies on solo-owned shops, per-shop policies (policy is per *guild*, applies to all its shops).

## 2. The core mechanic (why it's small)

For a guild shop the **guild bank is the trade counterparty**: `depositToShop`/`withdrawFromShop` move money to/from `shop.guildId`'s bank. So a tariff reduces to **multiplying the trade `cost` by a policy factor**; the bank captures the difference automatically.

- **SELL shop** (outsider pays the guild): `cost × (1 + r/100)`. The outsider pays more; the full amount lands in the guild bank, so the surcharge *is* the guild's profit.
- **BUY shop** (guild pays the outsider): `cost × (1 − r/100)`, floored at 0. The guild withdraws less from its bank and the outsider receives less; the guild keeps the difference.
- **EMBARGO**: the trade is rejected before any money moves.
- **Exempt** (solo buyer, own-guild buyer, or no policy): factor = 1.0 → byte-identical to today's behaviour.

## 3. Architecture (units)

Hexagonal/SPEAR. New units, each single-purpose:

### Domain
- **`GuildTradePolicy`** (`domain/guild/`) — value object: `ownerGuildId: String`, `targetGuildId: String`, `kind: PolicyKind { TARIFF, EMBARGO }`, `ratePct: Int` (ignored when EMBARGO). `init` validates `ownerGuildId != targetGuildId` and `ratePct in 0..1000`.
- **`GuildTradePolicyRepository`** (port) — `find(owner, target): GuildTradePolicy?`, `listByOwner(owner): List<GuildTradePolicy>`, `upsert(policy)`, `delete(owner, target)`, `deleteAllInvolving(guildId)` (owner OR target — for disband).

### Application
- **`GuildTradePolicyService`** (`@Service`) — the policy boundary.
  - `stanceFor(ownerGuildId: String, buyer: UUID, direction: SignDirection): TradeStance`
    - resolve `buyerGuild = guildProvider.guildOf(buyer)?.id`
    - `buyerGuild == null` **or** `buyerGuild == ownerGuildId` → `Allowed(1.0)`
    - else `repo.find(ownerGuildId, buyerGuild)`: `null` → `Allowed(1.0)`; `EMBARGO` → `Embargoed`; `TARIFF r` → `Allowed(factor)` where `factor = 1 + r/100.0` (SELL) or `maxOf(0.0, 1 − r/100.0)` (BUY).
  - Management methods (called by the GUI/command), each gated on `guildProvider.hasShopPermission(actor, ownerGuildId, MANAGE_SHOPS)`: `setTariff(actor, ownerGuildId, targetGuildId, ratePct)`, `setEmbargo(actor, ownerGuildId, targetGuildId)`, `clear(actor, ownerGuildId, targetGuildId)`, `list(ownerGuildId)`. Reject self-target.
  - `sealed interface TradeStance { data class Allowed(val factor: Double) : TradeStance; data object Embargoed : TradeStance }`

### Infrastructure
- **`GuildTradePolicyRepositorySql`** — JDBC, mirrors existing repos.
- **Migration** `V0xx__guild_trade_policies.sql`:
  ```sql
  CREATE TABLE IF NOT EXISTS guild_trade_policies (
    owner_guild_id  TEXT NOT NULL,
    target_guild_id TEXT NOT NULL,
    kind            TEXT NOT NULL,   -- TARIFF | EMBARGO
    rate_pct        INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (owner_guild_id, target_guild_id)
  );
  CREATE INDEX IF NOT EXISTS idx_gtp_owner ON guild_trade_policies(owner_guild_id);
  ```
- **`GuildProvider.listGuilds(): List<GuildRef>`** — new port method (+ LumaGuilds impl) for the picker.

### Changed units
- **`ContainerTradeService`** — at the top of `executeBuy`/`executeSell`, when `shop.guildId != null`: `val stance = policyService.stanceFor(shop.guildId.toString(), playerUuid, shop.direction)`. `Embargoed` → `Failure("<your guild> is embargoed by this shop's guild")`. `Allowed(factor)` → multiply the computed `cost` by `factor` (round to Long) before the existing withdraw/deposit. Inject `GuildTradePolicyService`.
- **`GuildDissolutionService.handle`** (M-16) — also call `policyRepo.deleteAllInvolving(guildId)` so a disbanded guild leaves no orphan policy rows (as owner or target).

### Interaction
- **Command** `/em guild policy` — `@Permission("enthusiamarket.guild.policy")` (default TRUE), player-only; resolves the actor's guild (`guildOf`), checks MANAGE_SHOPS, opens the menu. (Error if not in a guild / lacks perm.)
- **`GuildTradePolicyMenu`** — paginated list of the guild's current policies. Each icon: target guild name + "TARIFF 20%" or "EMBARGO". Click cycles: tariff ±5% (left/right click), shift-click toggles TARIFF⇄EMBARGO, drop/number-key removes (follow the repo's existing menu interaction idioms; final mapping decided in the plan). An **"+ Add policy"** button opens the picker.
- **`GuildPickerMenu`** — paginated `listGuilds()` minus the actor's own guild and already-policied guilds; selecting one creates a default **TARIFF 10%** row and returns to the policy menu in edit state.

## 4. Data flow — outsider buys at a tariffed guild SELL shop

```text
player clicks guild SELL shop sign → ContainerTradeService.executeBuy(shop, player)
  shop.guildId != null →
    stance = policyService.stanceFor(shop.guildId, player, SELL)
      buyerGuild = guildProvider.guildOf(player)?.id
      repo.find(ownerGuild, buyerGuild) = TARIFF 20 → Allowed(factor = 1.20)
  cost = round(shop.costAmount * 1.20)
  (existing flow) economy.withdraw(player, cost); depositToShop(guildId, cost) → guild bank +cost
  → outsider paid 120%, guild bank received 120%. Surcharge captured. Items delivered.
```

Embargo variant: `stance = Embargoed` → `Failure(...)`, no money/items move.

## 5. Error handling
- **Embargoed** → clean `Failure` message; nothing moves.
- **Buyer not in a guild / own guild / no policy** → factor 1.0, normal trade.
- **BUY tariff ≥ 100%** → factor floored at 0 (guild pays nothing; outsider gets nothing but the trade still "completes" — acceptable, it's an extreme protectionist setting the guild chose). The plan may instead reject ratePct ≥ 100 on BUY-relevant config; default behaviour is the floor.
- **Self-target / invalid rate** → rejected at the service (management), never persisted.
- **Stale policy referencing a disbanded guild** → cleaned on disband; until then a `find` for a non-existent target simply never matches a live buyer.
- **Management without MANAGE_SHOPS** → command/service rejects with a message.

## 6. Permissions
- `enthusiamarket.guild.policy` (default TRUE) — open the policy menu.
- In-guild authorisation via `guildProvider.hasShopPermission(actor, guildId, GuildProvider.GuildPermission.MANAGE_SHOPS)` — same gate as other guild-shop management (consistent with audit #4).

## 7. Build order (two sequential PRs — both touch guild/trade code)

**PR-A — the economic engine (no UI):** `GuildTradePolicy` + `PolicyKind`, repository port + Sql + migration, `GuildTradePolicyService` (`stanceFor` + management methods), the `ContainerTradeService` hook, the `GuildDissolutionService` cleanup tie-in. Fully TDD. Ships and is testable without any UI.

**PR-B — management UX:** `GuildProvider.listGuilds` (+ impl), `/em guild policy` command, `GuildTradePolicyMenu`, `GuildPickerMenu`. Wires the GUI to PR-A's service.

## 8. Testing (TDD)
- `GuildTradePolicyService.stanceFor`: solo buyer → 1.0; own-guild → 1.0; no policy → 1.0; embargo → Embargoed; tariff SELL → 1+r/100; tariff BUY → 1−r/100 floored at 0.
- Management methods: MANAGE_SHOPS gate (allow/deny), self-target rejected, rate clamp.
- `ContainerTradeService`: guild SELL shop with tariff charges `cost×factor` and the guild bank receives it; guild BUY shop pays `cost×factor`; embargo → Failure; **SOLO-owned shop trades unchanged** (regression guard); non-guild buyer at a guild shop pays base.
- `GuildTradePolicyRepositorySql`: upsert/find/listByOwner/delete/deleteAllInvolving round-trip.
- `GuildDissolutionService`: disband deletes policies where the guild is owner or target.

## 9. Open implementation details (decided in the plan, not blockers)
- Exact menu click-mapping (±5 / toggle / remove) — follow existing menu idioms.
- Migration version number — next free `V0xx`.
- Whether to also expose thin commands (`/em guild tariff …`) alongside the GUI — optional; GUI is the committed path.
