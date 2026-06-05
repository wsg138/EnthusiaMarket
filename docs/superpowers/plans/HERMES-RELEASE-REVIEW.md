# Hermes Release-Readiness Review — EnthusiaMarket (read-only audit)

**Executor model:** DeepSeek V4 Flash (OpenRouter)
**Mode:** Orchestrator dispatches **12 parallel read-only review subagents**, one per domain, then consolidates.
**Branch:** `main` (review only — NO code changes, NO commits).

Paste everything below the line into Hermes as the controlling prompt.

---

## ROLE

You are the **review orchestrator** for a pre-release audit of the EnthusiaMarket (EM) Paper plugin —
a Kotlin/Nexus market plugin (stalls = WorldGuard regions; players rent stalls and run container shops;
money via Vault, barter via an item vault; guilds can own stalls; initial mass auction seeds ownership).
ItemShops parity (6 sub-projects) just shipped. Before release, audit every release-critical flow for
**correctness, money-loss/duplication, and griefing vectors**.

This is **READ-ONLY**. Do NOT edit code, write files, or commit. Each subagent reads + traces + reports.
You collect, dedupe, rank, and produce ONE consolidated report.

## STEP 0 — Sync

```bash
cd /opt/data/EnthusiaMarket
git fetch origin
git checkout main
git pull
```

## STEP 1 — Dispatch 12 review subagents (parallel)

Dispatch one subagent per **Domain** below. Give each subagent: its domain brief verbatim, its
entry-point files, and the **Output contract**. They are independent — run them in parallel.

### Output contract (every subagent returns exactly this)

A ranked list. For each finding:
- **Severity:** `CRITICAL` (money loss/dupe, item dupe, security/grief, data corruption) · `MAJOR`
  (wrong behaviour, missing rollback, broken flow) · `MINOR` (edge case, UX, robustness).
- **Location:** `file:line` (or method).
- **What:** the bug/risk in one or two sentences.
- **Why it matters:** the player-visible or economic impact.
- **Repro/trace:** the sequence that triggers it.

If a domain is clean, say so explicitly. Do NOT propose code — just find and report. Prefer **few
high-confidence findings over many speculative ones**; flag uncertainty as `NEEDS-VERIFY`.

### Cross-cutting hunt list (every subagent applies to its domain)

- **Money:** is the player charged/credited exactly once? Is every failure path refunded? Can a
  validation that runs *after* a charge strand funds? Guild bank vs personal account — paid to the
  right place? Tax computed + routed correctly? `Int`/`Long` overflow on price?
- **Items:** can a GUI click or trade **duplicate** or **lose** items? Is `event.isCancelled = true`
  set on every inventory/GUI interaction? Are rollback legs complete (stock + payment + vault)?
- **State:** stall state-machine transitions valid? Double-acquire / double-settle races? Stale data
  rendered after a mutation?
- **Auth/grief:** can a non-owner/non-admin trigger an owner/admin action? Region/stall authority
  checked before the mutation? Perm node actually gates the path?
- **Main thread:** heavy work on the main thread (DB scans, `Bukkit.getOfflinePlayer(...).name`,
  full-table loads in a GUI/click)?

---

### Domain 1 — Initial mass auction lifecycle
**Files:** `application/AuctionLifecycleService.kt`, `interaction/gui/AuctionBrowserMenu.kt`, `infrastructure/listeners/PurchaseSignClickListener.kt` (bid path), `domain/auction/*`.
**Trace:** `startMassAuction` → `startAuctionForStall` (UNOWNED→AUCTIONING) → bids + anti-snipe → settle/`settleWithWinner` → OWNED + WG sync + limit gate; the no-bid revert. **Hunt:** double-settle, winner charged on limit-reject, item/escrow handling, anti-snipe extension correctness, state holes, settle of an already-settled/cancelled auction.

### Domain 2 — Purchase-sign buyout
**Files:** `application/StallBuyoutService.kt`, `infrastructure/listeners/PurchaseSignClickListener.kt`, `PurchaseSignCreateListener.kt`, `PurchaseSignRefreshListener.kt`, `PurchaseSignBreakListener.kt`, `application/PurchaseSignRenderer.kt`. **Trace:** sign create (price on line 3) → click → `buy` → charge → `awardTo` → WG sync. **Hunt:** price tampering/parse, double-buy race, `AlreadyOwned`/`AuctionLive` mutex, charge-then-fail-to-persist (the `throw` after withdraw), sign state vs stall state drift.

### Domain 3 — Sell offers
**Files:** `application/SellOfferService.kt`, `AdminCommands.kt` (`stall offer*`/`stall buy`). **Trace:** create offer → buy → tax → ownership; cancel. **Hunt:** tax computation + rounding + sink routing, self-purchase, offer↔auction mutex (REQ-263), money rollback, lingering offers on UNOWNED.

### Domain 4 — Guild ownership path
**Files:** `application/ShopGuildService.kt`, `StallBuyoutService.buyForGuild`, `infrastructure/lumaguilds/LumaGuildsGuildProvider.kt`, `domain/ports/GuildProvider.kt`, `domain/ports/RegionMemberSync.kt`. **Trace:** `buyForGuild` (MANAGE_SHOPS check → personal charge → `OwnerRef.guild` → WG sync best-effort). **Hunt:** who pays (personal vs guild bank — is that intended?), MANAGE_SHOPS gating bypass, the **WG-sync-for-guilds gap** (members can't build until manual add?), guild stalls vs personal-limit counting, guild bank deposit/withdraw correctness in barter/money shops.

### Domain 5 — Limits enforcement
**Files:** `application/LimitResolutionService.kt`, `StallOwnershipCounter.kt`, gates in `AuctionLifecycleService` + `StallBuyoutService`, `AdminCommands` (`limit`). **Trace:** `effectiveLimits` (no-group=unlimited, bypass) → `canClaim` at each acquisition. **Hunt:** SOLO-only counting correctness, per-kind cap, off-by-one at the cap boundary, count includes/excludes AUCTIONING/GRACE stalls correctly, `/em limit` accuracy, bypass node.

### Domain 6 — Rent, default, grace, eviction
**Files:** `application/RentCollectionService.kt`, `StallEvictionService.kt`, `StallRentExtensionService.kt`, `StallSellbackService.kt`, `domain/stall/RentTerms.kt`. **Trace:** scheduler tick → rent due → withdraw → default → grace → evict (+ schematic restore, WG clear, shop wipe). **Hunt:** rent amount (flat vs formula), grace timing/edge, eviction money + member/region cleanup, sellback refund math (periods prepaid), the **REQ-280 gap** (grace-expiry currently reverts to UNOWNED instead of emergency-auctioning), schematic-restore best-effort failures.

### Domain 7 — Shop creation in regions
**Files:** `infrastructure/listeners/SignPlaceListener.kt`, `ShopCreateListener.kt`, `interaction/gui/CreateShopMenu.kt`, `interaction/bedrock/BedrockCreateShopForm.kt`, `application/ShopFactory.kt`. **Trace:** place wall-sign on container in a stall → auth (`canManageStall`) → `[SELL]/[BUY]/[TRADE]` parse → persist + render. **Hunt:** region/stall resolution (WG), can a player place a shop in a stall they don't manage / outside any stall, guild-stall `[TRADE]` rejection, container-link validation, held-item edge cases, `[TRADE]` line-3 `"N material"` parsing, search-default.

### Domain 8 — Shop trading (money)
**Files:** `application/ContainerTradeService.kt` (executeBuy/executeSell), `interaction/gui/PurchaseMenu.kt`, `interaction/bedrock/BedrockPurchaseForm.kt`. **Trace:** right-click sign → menu → buy/sell → economy + item move with rollback. **Hunt:** the three-way rollback (money + chest + player inv), **guild-owned shop deposits to guild bank vs owner**, frozen shop, out-of-stock/container-full, double-click/double-trade, who-pays-who direction correctness ([SELL] vs [BUY]).

### Domain 9 — Barter trading + profits vault
**Files:** `application/ContainerTradeService.executeTrade`, `ShopVaultService.kt`, `infrastructure/persistence/ShopVaultRepositorySql.kt`, `interaction/gui/ShopVaultMenu.kt`, `infrastructure/commands/VaultCommands.kt`. **Trace:** `[TRADE]` click → pay N×cost into vault → receive M×sell; `/shopvault open` → withdraw. **Hunt:** rollback legs (stock/payment/vault), NBT key aggregation, **vault withdraw → re-deposit accounting** (item dupe/loss in the GUI), `event.isCancelled` on vault clicks, deserialize-failure resilience.

### Domain 10 — Money-flow cross-cut (whole economy)
**Files:** every `economy.withdraw/deposit/balance` + `guildProvider.bank*` call across `application/*`. **Trace:** enumerate every charge/credit and pair it with its rollback. **Hunt:** charge-before-final-validation, missing refund on any failure branch, double-charge, deposit to the wrong account, tax double-applied or never routed, `Double`/`Long` money-type mismatches, negative/zero price slipping through.

### Domain 11 — All menus / GUIs
**Files:** every `interaction/gui/*.kt` + `interaction/bedrock/*.kt` (`PurchaseMenu`, `ShopEditMenu`, `OwnedShopsMenu`, `SearchResultsMenu`, `ShopVaultMenu`, `BulkTrustMenu`, `DeleteShopsMenu`, `TrustManageMenu`, `CreateShopMenu`, `AuctionBrowserMenu`, Bedrock forms). **Hunt:** **`event.isCancelled = true` on EVERY `GuiItem` click** (a missing one = item theft/dupe from the GUI), pagination bounds (first/last page, empty), stale data after a mutating click (re-open/refresh), acting on the wrong shop/slot, main-thread `Bukkit.getOfflinePlayer().name` (vs the existing `OfflinePlayerNameCache`/`OwnerNameResolver`), close-on-action correctness.

### Domain 12 — Commands, permissions, lifecycle
**Files:** `infrastructure/commands/{AdminCommands,ShopCommands,VaultCommands}.kt`, `EnthusiaMarket.kt` (onEnable), `build.gradle.kts` perm DSL, generated `paper-plugin.yml`, `config/EnthusiaMarketConfig.kt`. **Hunt:** every `@Subcommand` has the right `@Permission` (no ungated admin verb), console-sender NPEs (players-only paths), the runtime perm-registration path (`registerDeclaredPermissions`) + node defaults, `onEnable` ordering/DI/Vault load-order/listener registration fail-closed, config defaults sane (tax, limits empty, retention, search-default).

---

## STEP 2 — Consolidate

Merge all 12 reports into ONE document:
- **Dedupe** cross-cutting findings (Domain 10/11 will overlap others — keep the clearest statement).
- **Rank** globally: all CRITICALs first, then MAJORs, then MINORs.
- For each: severity, location, what, impact, trace.
- End with a **release-blocker list** (the CRITICALs + any MAJOR on the money path) and a one-line
  go / no-go recommendation.

Post the consolidated report back. Do NOT fix anything — the coordinator triages and assigns fixes.

## FIRST ACTION

Sync `main`, then dispatch all 12 review subagents in parallel with their briefs + the output contract.
