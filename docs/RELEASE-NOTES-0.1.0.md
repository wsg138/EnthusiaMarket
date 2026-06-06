# EnthusiaMarket v0.1.0 — Release Notes

**Audience:** BadgersMC dev team
**Status:** Release-ready (all money-safety / item-dupe / corruption / auth blockers closed)

EnthusiaMarket (EM) is the Paper 1.21.x market plugin that **deprecates ItemShops** (p2wn),
**ARM**, and the **ARM-Guilds-Bridge**. Stalls are WorldGuard regions players rent; each stall hosts
container-linked sign shops. Built on the Nexus framework; money via Vault, item barter via a
per-owner item vault, guild ownership via LumaGuilds.

This release lands the full **ItemShops parity programme** (six sub-projects) plus a pre-release
security/economy audit.

---

## 1. ItemShops parity — complete (6 / 6)

| SP | Feature | PR |
|----|---------|----|
| **SP1** | **Shop management** — `/shop list / edit / trust / untrust / delete / breakdelete` | #26 |
| **SP2** | **Shop search** — `/shop search <item> [mode] [page]`, paginated results GUI with live stock, per-shop searchable opt-in | #28 |
| **SP5** | **Admin tooling** — `/shop admin view / info / remove / fix / breakothers`, admin teleport from search results (look-at targeting, one perm node) | #31 |
| **SP6** | **Misc / integration** — shop transaction log + `/shop history`, owner sale notifications (live + on-join), PAPI placeholders (`%enthusiamarket_shops_owned/total/sales_unseen%`), shift-right-click info card | #33 |
| **SP3** | **Barter / profits vault** — `[TRADE]` sign keyword (item-for-item), per-owner item vault + `/shopvault`, Paper-NBT (`serializeAsBytes`) storage | #34 |
| **SP4** | **Limits + market regions** — per-player stall-ownership limits (config groups + permission tiers), `/em limit`, gates on every acquisition path | #35 |

### Player-facing surface
- **`/shop`** — list, edit (GUI), trust/untrust, delete, breakdelete, **search**, **history**.
- **`/shopvault`** — withdraw collected barter payments.
- **Sign shops** — place a wall-sign on a container in a stall you manage: `[SELL]` (you sell for money), `[BUY]` (you buy for money), `[TRADE]` (item-for-item). Right-click to trade; shift-right-click for an info card.
- **`/em`** — admin: import, reload, list, auctions, stall members/kind/entity-limits, sellback, evict, **limit**, `/shop admin …`.

### Notable design decisions
- **Money vs barter:** `[SELL]`/`[BUY]` route through Vault; `[TRADE]` moves items into a per-owner vault. Guild-owned stalls can't host `[TRADE]` (no guild item-vault yet).
- **Limits:** count **SOLO-owned** stalls only — guild stalls never count toward a personal cap. A player in no configured limit group is **unlimited**.
- **Item serialization:** the vault uses Paper's NBT `serializeAsBytes`/`deserializeBytes` (data-converter-safe). Legacy `Shop.sellItem/costItem` still use the older serializer (unification is tracked tech-debt).
- **Permissions:** Nexus generates `paper-plugin.yml`, but EM also **registers nodes at runtime** in `onEnable` (Paper/Leaf didn't reliably honour the generated block on a server with no permission manager) — fixed in #30.

---

## 2. Pre-release audit + hotfixes

A 12-domain read-only audit found 32 issues (8 critical, 14 major, 10 minor). All money-loss,
item-duplication, corruption, and auth blockers are fixed across two passes.

### #37 — release blockers (8 critical + 4 money-safety major)
- **Barter item-dupe** — `deliverStock` discarded the chest `removeItem` leftover (hopper-drained chest minted items). Now rolls back the partial removal + refunds.
- **Buyout / rent / auction money-loss** — charge-then-persist paths now **refund on persistence failure** (buyout C2, rent-extension M5, auction-settle C3). Auction settle re-worked to **charge exactly once** (close-first + refund-on-failure, no re-settle).
- **Sign-price tampering** — only the author/owner may re-edit a purchase sign (C4).
- **Guild-shop auth** — `ShopGuildService` register/unregister now require shop ownership + guild membership (C5).
- **Guild WG ownership** — removed the duplicate `setOwner` that wrote a guild UUID into the region owner slot (C6).
- **Sign-shop tax** — was computed but never deposited; now routed to `taxDestination` (C7). Guild self-trade tax bleed closed; guild stalls rejected in the legacy sign-trade path (no bank routing there) (C8).
- **Limit backdoor** — sell-offer purchase now enforces the stall limit (M1).
- **Double-payout** — lingering sell offers cleaned on every UNOWNED transition (M3).
- **Free rent** — formula rent truncating to 0 floored to 1 (M4).
- **Vault item-loss** — withdraw→re-deposit guarded (M9).

### #38 — additional majors
- **Console-UUID corruption** — `/em auction start | bid | auction cancel` from console injected phantom UUIDs into the auction tables; now require a Player sender (M14).
- **Rent-extension money-loss** — refund on persistence failure (M5).
- **Refund robustness** — all refund paths check `economy.deposit`'s return (it returns `false`, doesn't throw) and log "manual refund required" on genuine failure.

---

## 3. Known limitations / deferred (post-release, NOT blockers)

- **Guild stalls pay no rent (M11).** `RentCollectionService` skips non-SOLO owners (no guild-bank integration). Guild-bank rent collection + guild eviction is a scoped feature.
- **REQ-280 emergency auction.** On grace-expiry a defaulted stall reverts to UNOWNED; the auto-re-auction bridge (`EMERGENCY_AUCTIONING`) is drafted but unwired.
- **Hardening backlog:** sell-offer WG sync (M2), stale members after eviction (M6), trade-rollback `addItem` checks (M10), `/shop search` main-thread chunk reads (M12), create-menu re-validation (M13), + 10 minors.
- **Tech-debt:** push `/shop search` filtering to SQL; async offline-player name resolution; unify `Shop` item fields onto `serializeAsBytes`.

---

## 4. Ops notes

- **Dependencies:** Vault (+ an economy provider — EnthusiaCurrency), WorldGuard, LumaGuilds; optional PlaceholderAPI (placeholders no-op without it). EnthusiaCurrency must load **before** EM (soft-depend wired).
- **Config:** `enthusiamarket.yaml`. `limits.<group>` ships **empty** (everyone unlimited until you configure groups + grant `enthusiamarket.limit.<group>`). Tune `rent.formulaPct` (default 1.0 = 1%/period). `shop.taxDestination` = a UUID to collect tax, else a no-op sink.
- **Permissions:** registered at runtime; admin nodes default op, player nodes default true. Bypass: `enthusiamarket.admin.bypasslimit`.
- **Migrations:** V001–V016 (SQLite primary; MariaDB supported). Auto-applied on enable.
- **Build/quality gate:** `./gradlew clean detekt test shadowJar` — detekt 0, full test suite green.
